// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.syntax;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;

/**
 * A tokenizer for the BUILD language.
 * <p>
 * See: <a href="https://docs.python.org/2/reference/lexical_analysis.html"/>
 * for some details.
 * <p>
 */
public final class Lexer {

  // Characters that can come immediately prior to an '=' character to generate
  // a different token
  private static final ImmutableMap<Character, TokenKind> EQUAL_TOKENS =
      ImmutableMap.<Character, TokenKind>builder()
          .put('=', TokenKind.EQUALS_EQUALS)
          .put('!', TokenKind.NOT_EQUALS)
          .put('>', TokenKind.GREATER_EQUALS)
          .put('<', TokenKind.LESS_EQUALS)
          .put('+', TokenKind.PLUS_EQUALS)
          .put('-', TokenKind.MINUS_EQUALS)
          .put('*', TokenKind.STAR_EQUALS)
          .put('/', TokenKind.SLASH_EQUALS)
          .put('%', TokenKind.PERCENT_EQUALS)
          .build();

  private final EventHandler eventHandler;

  // Input buffer and position
  private final char[] buffer;
  private int pos;

  /**
   * The part of the location information that is common to all LexerLocation
   * instances created by this Lexer.  Factored into a separate object so that
   * many Locations instances can share the same information as compactly as
   * possible, without closing over a Lexer instance.
   */
  private static class LocationInfo {
    final LineNumberTable lineNumberTable;
    final PathFragment filename;
    LocationInfo(PathFragment filename, LineNumberTable lineNumberTable) {
      this.filename = filename;
      this.lineNumberTable = lineNumberTable;
    }
  }

  private final LocationInfo locationInfo;

  // The stack of enclosing indentation levels; always contains '0' at the
  // bottom.
  private final Stack<Integer> indentStack = new Stack<>();

  /** Token to return */
  private Token token;

  private final List<Comment> comments;

  // The number of unclosed open-parens ("(", '{', '[') at the current point in
  // the stream. Whitespace is handled differently when this is nonzero.
  private int openParenStackDepth = 0;

  private boolean containsErrors;
  /**
   * True after a NEWLINE token.
   * In other words, we are outside an expression and we have to check the indentation.
   */
  private boolean checkIndentation;

  private int dents; // number of saved INDENT (>0) or OUTDENT (<0) tokens to return

  /**
   * Constructs a lexer which tokenizes the contents of the specified InputBuffer. Any errors during
   * lexing are reported on "handler".
   */
  public Lexer(
      ParserInputSource input, EventHandler eventHandler, LineNumberTable lineNumberTable) {
    this.buffer = input.getContent();
    this.pos = 0;
    this.eventHandler = eventHandler;
    this.locationInfo = new LocationInfo(input.getPath(), lineNumberTable);
    this.checkIndentation = true;
    this.comments = new ArrayList<>();
    this.dents = 0;

    indentStack.push(0);
  }

  public Lexer(ParserInputSource input, EventHandler eventHandler) {
    this(input, eventHandler, LineNumberTable.create(input.getContent(), input.getPath()));
  }

  List<Comment> getComments() {
    return comments;
  }

  /**
   * Returns the filename from which the lexer's input came. Returns an empty value if the input
   * came from a string.
   */
  public PathFragment getFilename() {
    return locationInfo.filename != null ? locationInfo.filename : PathFragment.EMPTY_FRAGMENT;
  }

  /**
   * Returns true if there were errors during scanning of this input file or
   * string. The Lexer may attempt to recover from errors, but clients should
   * not rely on the results of scanning if this flag is set.
   */
  public boolean containsErrors() {
    return containsErrors;
  }

  /**
   * Returns the next token, or EOF if it is the end of the file. It is an error to call nextToken()
   * after EOF has been returned.
   */
  public Token nextToken() {
    boolean afterNewline = token != null && token.kind == TokenKind.NEWLINE;
    token = null;
    tokenize();

    // Like Python, always end with a NEWLINE token, even if no '\n' in input:
    if (token.kind == TokenKind.EOF && !afterNewline) {
      token.kind = TokenKind.NEWLINE;
    }
    return token;
  }

  private void popParen() {
    if (openParenStackDepth == 0) {
      error("indentation error");
    } else {
      openParenStackDepth--;
    }
  }

  private void error(String message) {
     error(message, pos - 1, pos - 1);
  }

  private void error(String message, int start, int end)  {
    this.containsErrors = true;
    eventHandler.handle(Event.error(createLocation(start, end), message));
  }

  Location createLocation(int start, int end) {
    return new LexerLocation(locationInfo.lineNumberTable, start, end);
  }

  // Don't use an inner class as we don't want to close over the Lexer, only
  // the LocationInfo.
  @AutoCodec
  @Immutable
  static final class LexerLocation extends Location {
    private final LineNumberTable lineNumberTable;

    LexerLocation(LineNumberTable lineNumberTable, int startOffset, int endOffset) {
      super(startOffset, endOffset);
      this.lineNumberTable = lineNumberTable;
    }

    @Override
    public PathFragment getPath() {
      return lineNumberTable.getPath(getStartOffset());
    }

    @Override
    public LineAndColumn getStartLineAndColumn() {
      return lineNumberTable.getLineAndColumn(getStartOffset());
    }

    @Override
    public LineAndColumn getEndLineAndColumn() {
      // The end offset is the location *past* the actual end position --> subtract 1:
      int endOffset = getEndOffset() - 1;
      if (endOffset < 0) {
        endOffset = 0;
      }
      return lineNumberTable.getLineAndColumn(endOffset);
    }

    @Override
    public int hashCode() {
      return Objects.hash(lineNumberTable, internalHashCode());
    }

    @Override
    public boolean equals(Object other) {
      if (other == null || !other.getClass().equals(getClass())) {
        return false;
      }
      LexerLocation that = (LexerLocation) other;
      return internalEquals(that) && Objects.equals(this.lineNumberTable, that.lineNumberTable);
    }
  }

  /** invariant: symbol positions are half-open intervals. */
  private void setToken(Token s) {
    Preconditions.checkState(token == null);
    token = s;
  }

  /**
   * Parses an end-of-line sequence, handling statement indentation correctly.
   *
   * <p>UNIX newlines are assumed (LF). Carriage returns are always ignored.
   */
  private void newline() {
    if (openParenStackDepth > 0) {
      newlineInsideExpression(); // in an expression: ignore space
    } else {
      checkIndentation = true;
      setToken(new Token(TokenKind.NEWLINE, pos - 1, pos));
    }
  }

  private void newlineInsideExpression() {
    while (pos < buffer.length) {
      switch (buffer[pos]) {
        case ' ': case '\t': case '\r':
          pos++;
          break;
        default:
          return;
      }
    }
  }

  /** Computes indentation (updates dent) and advances pos. */
  private void computeIndentation() {
    // we're in a stmt: suck up space at beginning of next line
    int indentLen = 0;
    while (pos < buffer.length) {
      char c = buffer[pos];
      if (c == ' ') {
        indentLen++;
        pos++;
      } else if (c == '\r') {
        pos++;
      } else if (c == '\t') {
        error("Tabulations are not allowed for identation. Use spaces instead.");
        indentLen++;
        pos++;
      } else if (c == '\n') { // entirely blank line: discard
        indentLen = 0;
        pos++;
      } else if (c == '#') { // line containing only indented comment
        int oldPos = pos;
        while (pos < buffer.length && c != '\n') {
          c = buffer[pos++];
        }
        makeComment(oldPos, pos - 1, bufferSlice(oldPos, pos - 1));
        indentLen = 0;
      } else { // printing character
        break;
      }
    }

    if (pos == buffer.length) {
      indentLen = 0;
    } // trailing space on last line

    int peekedIndent = indentStack.peek();
    if (peekedIndent < indentLen) { // push a level
      indentStack.push(indentLen);
      dents++;

    } else if (peekedIndent > indentLen) { // pop one or more levels
      while (peekedIndent > indentLen) {
        indentStack.pop();
        dents--;
        peekedIndent = indentStack.peek();
      }

      if (peekedIndent < indentLen) {
        error("indentation error");
      }
    }
  }

  /**
   * Returns true if current position is in the middle of a triple quote
   * delimiter (3 x quot), and advances 'pos' by two if so.
   */
  private boolean skipTripleQuote(char quot) {
    if (lookaheadIs(0, quot) && lookaheadIs(1, quot)) {
      pos += 2;
      return true;
    } else {
      return false;
    }
  }

  /**
   * Scans a string literal delimited by 'quot', containing escape sequences.
   *
   * <p>ON ENTRY: 'pos' is 1 + the index of the first delimiter
   * ON EXIT: 'pos' is 1 + the index of the last delimiter.
   *
   * @return the string-literal token.
   */
  private Token escapedStringLiteral(char quot, boolean isRaw) {
    int literalStartPos = isRaw ? pos - 2 : pos - 1;
    boolean inTriplequote = skipTripleQuote(quot);
    // more expensive second choice that expands escaped into a buffer
    StringBuilder literal = new StringBuilder();
    while (pos < buffer.length) {
      char c = buffer[pos];
      pos++;
      switch (c) {
        case '\n':
          if (inTriplequote) {
            literal.append(c);
            break;
          } else {
            error("unterminated string literal at eol", literalStartPos, pos);
            return new Token(TokenKind.STRING, literalStartPos, pos, literal.toString());
          }
        case '\\':
          if (pos == buffer.length) {
            error("unterminated string literal at eof", literalStartPos, pos);
            return new Token(TokenKind.STRING, literalStartPos, pos, literal.toString());
          }
          if (isRaw) {
            // Insert \ and the following character.
            // As in Python, it means that a raw string can never end with a single \.
            literal.append('\\');
            if (lookaheadIs(0, '\r') && lookaheadIs(1, '\n')) {
              literal.append("\n");
              pos += 2;
            } else if (buffer[pos] == '\r' || buffer[pos] == '\n') {
              literal.append("\n");
              pos += 1;
            } else {
              literal.append(buffer[pos]);
              pos += 1;
            }
            break;
          }
          c = buffer[pos];
          pos++;
          switch (c) {
            case '\r':
              if (lookaheadIs(0, '\n')) {
                pos += 1;
                break;
              } else {
                break;
              }
            case '\n':
              // ignore end of line character
              break;
            case 'n':
              literal.append('\n');
              break;
            case 'r':
              literal.append('\r');
              break;
            case 't':
              literal.append('\t');
              break;
            case '\\':
              literal.append('\\');
              break;
            case '\'':
              literal.append('\'');
              break;
            case '"':
              literal.append('"');
              break;
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
              { // octal escape
                int octal = c - '0';
                if (pos < buffer.length) {
                  c = buffer[pos];
                  if (c >= '0' && c <= '7') {
                    pos++;
                    octal = (octal << 3) | (c - '0');
                    if (pos < buffer.length) {
                      c = buffer[pos];
                      if (c >= '0' && c <= '7') {
                        pos++;
                        octal = (octal << 3) | (c - '0');
                      }
                    }
                  }
                }
                if (octal > 0xff) {
                  error("octal escape sequence out of range (maximum is \\377)");
                }
                literal.append((char) (octal & 0xff));
                break;
              }
            case 'a':
            case 'b':
            case 'f':
            case 'N':
            case 'u':
            case 'U':
            case 'v':
            case 'x':
              // exists in Python but not implemented in Blaze => error
              error("escape sequence not implemented: \\" + c, literalStartPos, pos);
              break;
            default:
              // unknown char escape => "\literal"
              literal.append('\\');
              literal.append(c);
              break;
          }
          break;
        case '\'':
        case '"':
          if (c != quot || (inTriplequote && !skipTripleQuote(quot))) {
            // Non-matching quote, treat it like a regular char.
            literal.append(c);
          } else {
            // Matching close-delimiter, all done.
            return new Token(TokenKind.STRING, literalStartPos, pos, literal.toString());
          }
          break;
        default:
          literal.append(c);
          break;
      }
    }
    error("unterminated string literal at eof", literalStartPos, pos);
    return new Token(TokenKind.STRING, literalStartPos, pos, literal.toString());
  }

  /**
   * Scans a string literal delimited by 'quot'.
   *
   * <ul>
   * <li> ON ENTRY: 'pos' is 1 + the index of the first delimiter
   * <li> ON EXIT: 'pos' is 1 + the index of the last delimiter.
   * </ul>
   *
   * @param isRaw if true, do not escape the string.
   * @return the string-literal token.
   */
  private Token stringLiteral(char quot, boolean isRaw) {
    int literalStartPos = isRaw ? pos - 2 : pos - 1;
    int contentStartPos = pos;

    // Don't even attempt to parse triple-quotes here.
    if (skipTripleQuote(quot)) {
      pos -= 2;
      return escapedStringLiteral(quot, isRaw);
    }

    // first quick optimistic scan for a simple non-escaped string
    while (pos < buffer.length) {
      char c = buffer[pos++];
      switch (c) {
        case '\n':
          error("unterminated string literal at eol", literalStartPos, pos);
          Token t =
              new Token(
                  TokenKind.STRING, literalStartPos, pos, bufferSlice(contentStartPos, pos - 1));
          return t;
        case '\\':
          if (isRaw) {
            if (lookaheadIs(0, '\r') && lookaheadIs(1, '\n')) {
              // There was a CRLF after the newline. No shortcut possible, since it needs to be
              // transformed into a single LF.
              pos = contentStartPos;
              return escapedStringLiteral(quot, true);
            } else {
              pos++;
              break;
            }
          }
          // oops, hit an escape, need to start over & build a new string buffer
          pos = contentStartPos;
          return escapedStringLiteral(quot, false);
        case '\'':
        case '"':
          if (c == quot) {
            // close-quote, all done.
            return new Token(
                TokenKind.STRING, literalStartPos, pos, bufferSlice(contentStartPos, pos - 1));
          }
          break;
        default: // fall out
      }
    }

    // If the current position is beyond the end of the file, need to move it backwards
    // Possible if the file ends with `r"\` (unterminated raw string literal with a backslash)
    if (pos > buffer.length) {
      pos = buffer.length;
    }

    error("unterminated string literal at eof", literalStartPos, pos);
    return new Token(TokenKind.STRING, literalStartPos, pos, bufferSlice(contentStartPos, pos));
  }

  private static final Map<String, TokenKind> keywordMap = new HashMap<>();

  static {
    keywordMap.put("and", TokenKind.AND);
    keywordMap.put("as", TokenKind.AS);
    keywordMap.put("assert", TokenKind.ASSERT);
    keywordMap.put("break", TokenKind.BREAK);
    keywordMap.put("class", TokenKind.CLASS);
    keywordMap.put("continue", TokenKind.CONTINUE);
    keywordMap.put("def", TokenKind.DEF);
    keywordMap.put("del", TokenKind.DEL);
    keywordMap.put("elif", TokenKind.ELIF);
    keywordMap.put("else", TokenKind.ELSE);
    keywordMap.put("except", TokenKind.EXCEPT);
    keywordMap.put("finally", TokenKind.FINALLY);
    keywordMap.put("for", TokenKind.FOR);
    keywordMap.put("from", TokenKind.FROM);
    keywordMap.put("global", TokenKind.GLOBAL);
    keywordMap.put("if", TokenKind.IF);
    keywordMap.put("import", TokenKind.IMPORT);
    keywordMap.put("in", TokenKind.IN);
    keywordMap.put("is", TokenKind.IS);
    keywordMap.put("lambda", TokenKind.LAMBDA);
    keywordMap.put("load", TokenKind.LOAD);
    keywordMap.put("nonlocal", TokenKind.NONLOCAL);
    keywordMap.put("not", TokenKind.NOT);
    keywordMap.put("or", TokenKind.OR);
    keywordMap.put("pass", TokenKind.PASS);
    keywordMap.put("raise", TokenKind.RAISE);
    keywordMap.put("return", TokenKind.RETURN);
    keywordMap.put("try", TokenKind.TRY);
    keywordMap.put("while", TokenKind.WHILE);
    keywordMap.put("with", TokenKind.WITH);
    keywordMap.put("yield", TokenKind.YIELD);
  }

  /**
   * Scans an identifier or keyword.
   *
   * <p>ON ENTRY: 'pos' is 1 + the index of the first char in the identifier.
   * ON EXIT: 'pos' is 1 + the index of the last char in the identifier.
   *
   * @return the identifier or keyword token.
   */
  private Token identifierOrKeyword() {
    int oldPos = pos - 1;
    String id = scanIdentifier();
    TokenKind kind = keywordMap.get(id);
    return (kind == null)
        ? new Token(TokenKind.IDENTIFIER, oldPos, pos, id)
        : new Token(kind, oldPos, pos, null);
  }

  private String scanIdentifier() {
    int oldPos = pos - 1;
    while (pos < buffer.length) {
      switch (buffer[pos]) {
        case '_':
        case 'a': case 'b': case 'c': case 'd': case 'e': case 'f':
        case 'g': case 'h': case 'i': case 'j': case 'k': case 'l':
        case 'm': case 'n': case 'o': case 'p': case 'q': case 'r':
        case 's': case 't': case 'u': case 'v': case 'w': case 'x':
        case 'y': case 'z':
        case 'A': case 'B': case 'C': case 'D': case 'E': case 'F':
        case 'G': case 'H': case 'I': case 'J': case 'K': case 'L':
        case 'M': case 'N': case 'O': case 'P': case 'Q': case 'R':
        case 'S': case 'T': case 'U': case 'V': case 'W': case 'X':
        case 'Y': case 'Z':
        case '0': case '1': case '2': case '3': case '4': case '5':
        case '6': case '7': case '8': case '9':
          pos++;
          break;
       default:
          return bufferSlice(oldPos, pos);
      }
    }
    return bufferSlice(oldPos, pos);
  }

  private String scanInteger() {
    int oldPos = pos - 1;
    while (pos < buffer.length) {
      char c = buffer[pos];
      switch (c) {
        case 'X': case 'x': // for hexadecimal prefix
        case 'O': case 'o': // for octal prefix
        case 'a': case 'A':
        case 'b': case 'B':
        case 'c': case 'C':
        case 'd': case 'D':
        case 'e': case 'E':
        case 'f': case 'F':
        case '0': case '1':
        case '2': case '3':
        case '4': case '5':
        case '6': case '7':
        case '8': case '9':
          pos++;
          break;
        default:
          return bufferSlice(oldPos, pos);
      }
    }
    // TODO(bazel-team): (2009) to do roundtripping when we evaluate the integer
    // constants, we must save the actual text of the tokens, not just their
    // integer value.

    return bufferSlice(oldPos, pos);
  }

  /**
   * Scans an integer literal.
   *
   * <p>ON ENTRY: 'pos' is 1 + the index of the first char in the literal.
   * ON EXIT: 'pos' is 1 + the index of the last char in the literal.
   *
   * @return the integer token.
   */
  private Token integer() {
    int oldPos = pos - 1;
    String literal = scanInteger();

    final String substring;
    final int radix;
    if (literal.startsWith("0x") || literal.startsWith("0X")) {
      radix = 16;
      substring = literal.substring(2);
    } else if (literal.startsWith("0o") || literal.startsWith("0O")) {
      radix = 8;
      substring = literal.substring(2);
    } else if (literal.startsWith("0") && literal.length() > 1) {
      radix = 8;
      substring = literal.substring(1);
    } else {
      radix = 10;
      substring = literal;
    }

    int value = 0;
    try {
      value = Integer.parseInt(substring, radix);
    } catch (NumberFormatException e) {
      error("invalid base-" + radix + " integer constant: " + literal);
    }

    return new Token(TokenKind.INT, oldPos, pos, value);
  }

  /**
   * Tokenizes a two-char operator.
   * @return true if it tokenized an operator
   */
  private boolean tokenizeTwoChars() {
    if (pos + 2 >= buffer.length) {
      return false;
    }
    char c1 = buffer[pos];
    char c2 = buffer[pos + 1];
    TokenKind tok = null;
    if (c2 == '=') {
      tok = EQUAL_TOKENS.get(c1);
    } else if (c2 == '*' && c1 == '*') {
      tok = TokenKind.STAR_STAR;
    }
    if (tok == null) {
      return false;
    } else {
      setToken(new Token(tok, pos, pos + 2));
      return true;
    }
  }

  /** Test if the character at pos+p is c. */
  private boolean lookaheadIs(int p, char c) {
    return pos + p < buffer.length && buffer[pos + p] == c;
  }

  /**
   * Performs tokenization of the character buffer of file contents provided to the constructor.
   * Advances pos and sets the token variable.
   */
  private void tokenize() {
    if (checkIndentation) {
      checkIndentation = false;
      computeIndentation();
    }

    // Return saved indentation tokens.
    if (dents != 0) {
      if (dents < 0) {
        dents++;
        setToken(new Token(TokenKind.OUTDENT, pos - 1, pos));
      } else {
        dents--;
        setToken(new Token(TokenKind.INDENT, pos - 1, pos));
      }
      return;
    }

    while (pos < buffer.length) {
      if (tokenizeTwoChars()) {
        pos += 2;
        return;
      }
      char c = buffer[pos];
      pos++;
      switch (c) {
      case '{': {
        setToken(new Token(TokenKind.LBRACE, pos - 1, pos));
        openParenStackDepth++;
        break;
      }
      case '}': {
        setToken(new Token(TokenKind.RBRACE, pos - 1, pos));
        popParen();
        break;
      }
      case '(': {
        setToken(new Token(TokenKind.LPAREN, pos - 1, pos));
        openParenStackDepth++;
        break;
      }
      case ')': {
        setToken(new Token(TokenKind.RPAREN, pos - 1, pos));
        popParen();
        break;
      }
      case '[': {
        setToken(new Token(TokenKind.LBRACKET, pos - 1, pos));
        openParenStackDepth++;
        break;
      }
      case ']': {
        setToken(new Token(TokenKind.RBRACKET, pos - 1, pos));
        popParen();
        break;
      }
      case '>': {
        setToken(new Token(TokenKind.GREATER, pos - 1, pos));
        break;
      }
      case '<': {
        setToken(new Token(TokenKind.LESS, pos - 1, pos));
        break;
      }
      case ':': {
        setToken(new Token(TokenKind.COLON, pos - 1, pos));
        break;
      }
      case ',': {
        setToken(new Token(TokenKind.COMMA, pos - 1, pos));
        break;
      }
      case '+': {
        setToken(new Token(TokenKind.PLUS, pos - 1, pos));
        break;
      }
      case '-': {
        setToken(new Token(TokenKind.MINUS, pos - 1, pos));
        break;
      }
      case '|': {
        setToken(new Token(TokenKind.PIPE, pos - 1, pos));
        break;
      }
      case '=': {
        setToken(new Token(TokenKind.EQUALS, pos - 1, pos));
        break;
      }
      case '%': {
        setToken(new Token(TokenKind.PERCENT, pos - 1, pos));
        break;
      }
      case '/': {
        if (lookaheadIs(0, '/') && lookaheadIs(1, '=')) {
          setToken(new Token(TokenKind.SLASH_SLASH_EQUALS, pos - 1, pos + 2));
          pos += 2;
        } else if (lookaheadIs(0, '/')) {
          setToken(new Token(TokenKind.SLASH_SLASH, pos - 1, pos + 1));
          pos += 1;
        } else {
          // /= is handled by tokenizeTwoChars.
          setToken(new Token(TokenKind.SLASH, pos - 1, pos));
        }
        break;
      }
      case ';': {
        setToken(new Token(TokenKind.SEMI, pos - 1, pos));
        break;
      }
      case '.': {
        setToken(new Token(TokenKind.DOT, pos - 1, pos));
        break;
      }
      case '*': {
        setToken(new Token(TokenKind.STAR, pos - 1, pos));
        break;
      }
      case ' ':
      case '\t':
      case '\r': {
        /* ignore */
        break;
      }
      case '\\': {
        // Backslash character is valid only at the end of a line (or in a string)
        if (lookaheadIs(0, '\n')) {
          pos += 1;  // skip the end of line character
        } else if (lookaheadIs(0, '\r') && lookaheadIs(1, '\n')) {
          pos += 2;  // skip the CRLF at the end of line
        } else {
          setToken(new Token(TokenKind.ILLEGAL, pos - 1, pos, Character.toString(c)));
        }
        break;
      }
      case '\n': {
        newline();
        break;
      }
      case '#': {
        int oldPos = pos - 1;
        while (pos < buffer.length) {
          c = buffer[pos];
          if (c == '\n') {
            break;
          } else {
            pos++;
          }
        }
        makeComment(oldPos, pos, bufferSlice(oldPos, pos));
        break;
      }
      case '\'':
      case '\"': {
        setToken(stringLiteral(c, false));
        break;
      }
      default: {
        // detect raw strings, e.g. r"str"
        if (c == 'r' && pos < buffer.length
            && (buffer[pos] == '\'' || buffer[pos] == '\"')) {
          c = buffer[pos];
          pos++;
          setToken(stringLiteral(c, true));
          break;
        }

        if (c >= '0' && c <= '9') {
          setToken(integer());
        } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_') {
          setToken(identifierOrKeyword());
        } else {
          error("invalid character: '" + c + "'");
        }
        break;
      } // default
      } // switch
      if (token != null) { // stop here if we scanned a token
        return;
      }
    } // while

    if (indentStack.size() > 1) { // top of stack is always zero
      setToken(new Token(TokenKind.NEWLINE, pos - 1, pos));
      while (indentStack.size() > 1) {
        indentStack.pop();
        dents--;
      }
      return;
    }

    setToken(new Token(TokenKind.EOF, pos, pos));
  }

  /**
   * Returns the string at the current line, minus the new line.
   *
   * @param line the line from which to retrieve the String, 1-based
   * @return the text of the line
   */
  public String stringAtLine(int line) {
    Pair<Integer, Integer> offsets = locationInfo.lineNumberTable.getOffsetsForLine(line);
    return bufferSlice(offsets.first, offsets.second);
  }

  /**
   * Returns parts of the source buffer based on offsets
   *
   * @param start the beginning offset for the slice
   * @param end the offset immediately following the slice
   * @return the text at offset start with length end - start
   */
  private String bufferSlice(int start, int end) {
    return new String(this.buffer, start, end - start);
  }

  private void makeComment(int start, int end, String content) {
    comments.add(ASTNode.setLocation(createLocation(start, end), new Comment(content)));
  }
}
