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
package com.google.devtools.build.lib.runtime.commands;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.buildtool.OutputDirectoryLinksUtils;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.StarlarkOptionsParser;
import com.google.devtools.build.lib.runtime.commands.events.CleanStartingEvent;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.CleanCommand.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.util.CommandBuilder;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.ProcessUtils;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.LogManager;

/** Implements 'blaze clean'. */
@Command(
    name = "clean",
    builds = true, // Does not, but people expect build options to be there
    allowResidue = true, // Does not, but need to allow so we can ignore Starlark options.
    writeCommandLog = false, // Do not create a command.log, otherwise we couldn't delete it.
    options = {CleanCommand.Options.class},
    help = "resource:clean.txt",
    shortDescription = "Removes output files and optionally stops the server.",
    // TODO(bazel-team): Remove this - we inherit a huge number of unused options.
    inherits = {BuildCommand.class}
)
public final class CleanCommand implements BlazeCommand {
  /** An interface for special options for the clean command. */
  public static class Options extends OptionsBase {
    @Option(
        name = "expunge",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
        help =
            "If true, clean removes the entire working tree for this %{product} instance, "
                + "which includes all %{product}-created temporary and build output files, "
                + "and stops the %{product} server if it is running."
    )
    public boolean expunge;

    @Option(
        name = "expunge_async",
        defaultValue = "null",
        expansion = {"--expunge", "--async"},
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
        help =
            "If specified, clean asynchronously removes the entire working tree for "
                + "this %{product} instance, which includes all %{product}-created temporary and "
                + "build output files, and stops the %{product} server if it is running. When "
                + "this command completes, it will be safe to execute new commands in the same "
                + "client, even though the deletion may continue in the background."
    )
    public Void expungeAsync;

    @Option(
        name = "async",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
        help =
            "If true, output cleaning is asynchronous. When this command completes, it will be safe "
                + "to execute new commands in the same client, even though the deletion may continue "
                + "in the background."
    )
    public boolean async;
  }

  private final OS os;

  public CleanCommand() {
    this(OS.getCurrent());
  }

  @VisibleForTesting
  public CleanCommand(OS os) {
    this.os = os;
  }

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    // Assert that the only residue is starlark options and ignore them.
    Pair<ImmutableList<String>, ImmutableList<String>> starlarkOptionsAndResidue =
        StarlarkOptionsParser.removeStarlarkOptions(options.getResidue());
    ImmutableList<String> removedStarlarkOptions = starlarkOptionsAndResidue.getFirst();
    ImmutableList<String> residue = starlarkOptionsAndResidue.getSecond();
    if (!removedStarlarkOptions.isEmpty()) {
      env.getReporter()
          .handle(
              Event.warn(
                  "Blaze clean does not support starlark options. Ignoring options: "
                      + removedStarlarkOptions));
    }
    if (!residue.isEmpty()) {
      String message = "Unknown arguments" + residue;
      env.getReporter().handle(Event.error(message));
      return BlazeCommandResult.exitCode(ExitCode.COMMAND_LINE_ERROR);
    }

    Options cleanOptions = options.getOptions(Options.class);
    boolean async = cleanOptions.async;
    env.getEventBus().post(new NoBuildEvent());

    // TODO(dmarting): Deactivate expunge_async on non-Linux platform until we completely fix it
    // for non-Linux platforms (https://github.com/bazelbuild/bazel/issues/1906).
    // MacOS and FreeBSD support setsid(2) but don't have /usr/bin/setsid, so if we wanted to
    // support --expunge_async on these platforms, we'd have to write a wrapper that calls setsid(2)
    // and exec(2).
    boolean asyncSupport = os == OS.LINUX;
    if (async && !asyncSupport) {
      String fallbackName = cleanOptions.expunge ? "--expunge" : "synchronous clean";
      env.getReporter()
          .handle(
              Event.info(
                  null /*location*/,
                  "--async cannot be used on non-Linux platforms, falling back to "
                      + fallbackName));
      async = false;
    }

    String cleanBanner =
        (async || !asyncSupport)
            ? "Starting clean."
            : "Starting clean (this may take a while). "
                + "Consider using --async if the clean takes more than several minutes.";

    env.getEventBus().post(new CleanStartingEvent(options));
    env.getReporter().handle(Event.info(null /*location*/, cleanBanner));

    try {
      String symlinkPrefix =
          options
              .getOptions(BuildRequestOptions.class)
              .getSymlinkPrefix(env.getRuntime().getProductName());
      return actuallyClean(env, env.getOutputBase(), cleanOptions.expunge, async, symlinkPrefix);
    } catch (CleanException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.failureDetail(e.getFailureDetail());
    } catch (InterruptedException e) {
      String message = "clean interrupted";
      env.getReporter().handle(Event.error(message));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(
              message, FailureDetails.Interrupted.Code.CLEAN_COMMAND));
    }
  }

  private static void asyncClean(CommandEnvironment env, Path path, String pathItemName)
      throws IOException, CommandException {
    String tempBaseName = path.getBaseName() + "_tmp_" + ProcessUtils.getpid();

    // Keeping tempOutputBase in the same directory ensures it remains in the
    // same file system, and therefore the mv will be atomic and fast.
    Path tempPath = path.getParentDirectory().getChild(tempBaseName);
    path.renameTo(tempPath);
    env.getReporter()
        .handle(Event.info(null, pathItemName + " moved to " + tempPath + " for deletion"));

    // Daemonize the shell and use the double-fork idiom to ensure that the shell
    // exits even while the "rm -rf" command continues.
    String command =
        String.format(
            "exec >&- 2>&- <&- && (/usr/bin/setsid /bin/rm -rf %s &)&",
            ShellEscaper.escapeString(tempPath.getPathString()));

    logger.atInfo().log("Executing shell command %s", ShellEscaper.escapeString(command));

    // Doesn't throw iff command exited and was successful.
    new CommandBuilder()
        .addArg(command)
        .useShell(true)
        .setWorkingDir(tempPath.getParentDirectory())
        .build()
        .execute();
  }

  private BlazeCommandResult actuallyClean(
      CommandEnvironment env, Path outputBase, boolean expunge, boolean async, String symlinkPrefix)
      throws CleanException, InterruptedException {
    BlazeRuntime runtime = env.getRuntime();
    String workspaceDirectory = env.getWorkspace().getBaseName();
    if (env.getOutputService() != null) {
      try {
        env.getOutputService().clean();
      } catch (ExecException e) {
        throw new CleanException(Code.OUTPUT_SERVICE_CLEAN_FAILURE, e);
      }
    }
    try {
      env.getBlazeWorkspace().clearCaches();
    } catch (IOException e) {
      throw new CleanException(Code.ACTION_CACHE_CLEAN_FAILURE, e);
    }
    if (expunge && !async) {
      logger.atInfo().log("Expunging...");
      runtime.prepareForAbruptShutdown();
      // Close java.log.
      LogManager.getLogManager().reset();
      // Close the default stdout/stderr.
      try {
        if (FileDescriptor.out.valid()) {
          new FileOutputStream(FileDescriptor.out).close();
        }
        if (FileDescriptor.err.valid()) {
          new FileOutputStream(FileDescriptor.err).close();
        }
      } catch (IOException e) {
        throw new CleanException(Code.OUT_ERR_CLOSE_FAILURE, e);
      }
      // Close the redirected stdout/stderr.
      System.out.close();
      System.err.close();
      // Delete the big subdirectories with the important content first--this
      // will take the most time. Then quickly delete the little locks, logs
      // and links right before we exit. Once the lock file is gone there will
      // be a small possibility of a server race if a client is waiting, but
      // all significant files will be gone by then.
      try {
        outputBase.deleteTreesBelow();
        outputBase.deleteTree();
      } catch (IOException e) {
        throw new CleanException(Code.OUTPUT_BASE_DELETE_FAILURE, e);
      }
    } else if (expunge && async) {
      logger.atInfo().log("Expunging asynchronously...");
      runtime.prepareForAbruptShutdown();
      try {
        asyncClean(env, outputBase, "Output base");
      } catch (IOException e) {
        throw new CleanException(Code.OUTPUT_BASE_TEMP_MOVE_FAILURE, e);
      } catch (CommandException e) {
        throw new CleanException(Code.ASYNC_OUTPUT_BASE_DELETE_FAILURE, e);
      }
    } else {
      logger.atInfo().log("Output cleaning...");
      env.getBlazeWorkspace().resetEvaluator();
      Path execroot = outputBase.getRelative("execroot");
      if (execroot.exists()) {
        logger.atFinest().log("Cleaning %s%s", execroot, async ? " asynchronously..." : "");
        if (async) {
          try {
            asyncClean(env, execroot, "Output tree");
          } catch (IOException e) {
            throw new CleanException(Code.EXECROOT_TEMP_MOVE_FAILURE, e);
          } catch (CommandException e) {
            throw new CleanException(Code.ASYNC_EXECROOT_DELETE_FAILURE, e);
          }
        } else {
          try {
            execroot.deleteTreesBelow();
          } catch (IOException e) {
            throw new CleanException(Code.EXECROOT_DELETE_FAILURE, e);
          }
        }
      }
    }
    // remove convenience links
    OutputDirectoryLinksUtils.removeOutputDirectoryLinks(
        runtime.getRuleClassProvider().getSymlinkDefinitions(),
        workspaceDirectory,
        env.getWorkspace(),
        env.getReporter(),
        symlinkPrefix,
        env.getRuntime().getProductName());

    // shutdown on expunge cleans
    if (expunge) {
      return BlazeCommandResult.shutdownOnSuccess();
    }
    System.gc();
    return BlazeCommandResult.success();
  }

  private static class CleanException extends Exception {
    private final FailureDetails.CleanCommand.Code detailedCode;

    private CleanException(FailureDetails.CleanCommand.Code detailedCode, Exception e) {
      super(Strings.nullToEmpty(e.getMessage()), e);
      this.detailedCode = detailedCode;
    }

    private FailureDetail getFailureDetail() {
      return FailureDetail.newBuilder()
          .setMessage(getMessage())
          .setCleanCommand(FailureDetails.CleanCommand.newBuilder().setCode(detailedCode))
          .build();
    }
  }
}
