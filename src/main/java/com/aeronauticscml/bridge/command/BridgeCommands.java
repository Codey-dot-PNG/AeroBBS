package com.aeronauticscml.bridge.command;

import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import com.aeronauticscml.bridge.recording.RecordingService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class BridgeCommands {
    private BridgeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aeronauticscml")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("start").executes(BridgeCommands::doStart))
                .then(Commands.literal("stop").executes(BridgeCommands::doStop))
                .then(Commands.literal("status").executes(BridgeCommands::doStatus))
                .then(Commands.literal("session")
                        .then(Commands.literal("start").executes(BridgeCommands::doStart))
                        .then(Commands.literal("stop").executes(BridgeCommands::doStop))
                )
        );
    }

    private static RecordingService service() {
        AeronauticsCmlBridge bridge = AeronauticsCmlBridge.get();
        return bridge == null ? null : bridge.recordingService();
    }

    private static int doStart(CommandContext<CommandSourceStack> ctx) {
        RecordingService service = service();
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("Recording service is not initialised yet."));
            return 0;
        }
        if (!service.isReady()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bridge is not ready. Aeronautics provider=" +
                            (service.status().aeronauticsAvailable() ? "OK" : "MISSING")));
            return 0;
        }
        service.start();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Recording session opened via " + service.status().recorderBackend()), true);
        return 1;
    }

    private static int doStop(CommandContext<CommandSourceStack> ctx) {
        RecordingService service = service();
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("Recording service is not initialised yet."));
            return 0;
        }
        RecordingService.RecordingStatus before = service.status();
        service.stop();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Stopped. Frames this session: " + before.framesThisSession() +
                        " | Ships: " + before.activeShips()), true);
        return 1;
    }

    private static int doStatus(CommandContext<CommandSourceStack> ctx) {
        RecordingService service = service();
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("Recording service is not initialised yet."));
            return 0;
        }
        RecordingService.RecordingStatus s = service.status();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[aeronauticscml] recording=" + s.recording() +
                        " backend=" + s.recorderBackend() +
                        " aeronautics=" + (s.aeronauticsAvailable() ? "OK" : "MISSING") +
                        " activeShips=" + s.activeShips() +
                        " sessionFrames=" + s.framesThisSession() +
                        " lifetimeFrames=" + s.totalFramesLifetime() +
                        " lastTick=" + s.lastCapturedTick()
        ), false);
        return 1;
    }
}