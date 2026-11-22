package com.paulorchard.islandcraft.dunesofarrakis;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** Creative hide-sand toggle: /arrakis sand hide|show and an unbound key. Usable in creative and spectator only; resets on leaving the world. */
@EventBusSubscriber(modid = DunesOfArrakis.MOD_ID, value = Dist.CLIENT)
public final class SandToggleClient {
    private static final KeyMapping TOGGLE = new KeyMapping("key.islandcraft_dunesofarrakis.toggle_sand",
        InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.CREATIVE);

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("arrakis").then(Commands.literal("sand")
            .then(Commands.literal("hide").executes(c -> set(true)))
            .then(Commands.literal("show").executes(c -> set(false)))));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        while (TOGGLE.consumeClick()) set(!SandView.isHidden());
        if (SandView.isHidden() && !allowed(player)) {   // left creative or spectator: the sand comes back
            SandView.setHidden(false);
            reload();
            player.sendSystemMessage(Component.literal("Dune Sand shown again (hiding works only in creative or spectator)."));
        }
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SandView.setHidden(false);
    }

    private static boolean allowed(LocalPlayer player) {
        return player.isCreative() || player.isSpectator();
    }

    private static int set(boolean hide) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return 0;
        if (hide && !allowed(player)) {
            player.sendSystemMessage(Component.literal("Hiding Dune Sand works only in creative or spectator mode."));
            return 0;
        }
        if (SandView.isHidden() != hide) {
            SandView.setHidden(hide);
            reload();
        }
        player.sendSystemMessage(Component.literal(hide ? "Dune Sand hidden (still solid)." : "Dune Sand shown."));
        return Command.SINGLE_SUCCESS;
    }

    private static void reload() {
        Minecraft.getInstance().levelRenderer.allChanged();
    }

    private SandToggleClient() {}
}
