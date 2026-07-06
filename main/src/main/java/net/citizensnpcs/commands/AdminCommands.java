package net.citizensnpcs.commands;

import java.util.Map;
import java.util.WeakHashMap;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.monitor.PacketMonitorService;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.StringHelper;

@Requirements
public class AdminCommands {
    private final Citizens plugin;
    private final Map<CommandSender, Long> reloadTimeouts = new WeakHashMap<>();

    public AdminCommands(Citizens plugin) {
        this.plugin = plugin;
    }

    @Command(aliases = { "citizens" }, desc = "", max = 0, permission = "citizens.admin")
    public void citizens(CommandContext args, CommandSender sender, NPC npc) throws CommandException {
        Messaging.send(sender, StringHelper.wrapHeader("<green>Citizens v" + plugin.getDescription().getVersion()));
        Messaging.send(sender, "     <yellow>-- <green>Author: fullwall");
        Messaging.send(sender, "     <yellow>-- <green><click:open_url:" + plugin.getDescription().getWebsite()
                + "><hover:show_text:Citizens website including wiki><u>Website</hover></click> <click:open_url:https://discord.gg/Q6pZGSR><hover:show_text:Citizens Support Discord><u>Support</hover></click>");
    }

    @Command(
            aliases = { "citizens" },
            usage = "packetmonitor status|url|reset|enable|disable",
            desc = "",
            modifiers = { "packetmonitor" },
            min = 1,
            max = 2,
            permission = "citizens.admin.packetmonitor")
    public void packetMonitor(CommandContext args, CommandSender sender, NPC npc) throws CommandException {
        PacketMonitorService monitor = plugin.getPacketMonitorService();
        if (monitor == null)
            throw new CommandException("Packet monitor is not initialised.");

        String action = args.getString(1, "status").toLowerCase();
        if (action.equals("status")) {
            sendPacketMonitorStatus(sender, monitor);
        } else if (action.equals("url")) {
            sendPacketMonitorUrl(sender, monitor);
        } else if (action.equals("reset")) {
            monitor.reset();
            Messaging.send(sender, "<green>Packet monitor statistics reset.</green>");
        } else if (action.equals("enable")) {
            if (!monitor.enableRuntime()) {
                Messaging.send(sender, "<red>Packet monitor is disabled in patched-config.yml.</red>");
                return;
            }
            sendPacketMonitorStatus(sender, monitor);
        } else if (action.equals("disable")) {
            monitor.disableRuntime();
            Messaging.send(sender, "<yellow>Packet monitor disabled.</yellow>");
        } else {
            throw new CommandException("Usage: /citizens packetmonitor status|url|reset|enable|disable");
        }
    }

    private void sendPacketMonitorStatus(CommandSender sender, PacketMonitorService monitor) {
        Messaging.send(sender, StringHelper.wrapHeader("<green>Citizens Packet Monitor</green>"));
        if (!monitor.isRunning()) {
            Messaging.send(sender,
                    "     <yellow>-- <green>Status: <red>stopped</red> "
                            + "<click:run_command:/citizens packetmonitor enable><hover:show_text:Start packet monitor for 600 seconds><yellow><u>Start 600s diagnostic</u></yellow></hover></click>");
            return;
        }
        Messaging.send(sender, "     <yellow>-- <green>Status: running <gray>(" + monitor.getCaptureMode() + " / "
                + monitor.getRemainingSeconds() + "s)</gray>");
        sendPacketMonitorUrl(sender, monitor);
    }

    private void sendPacketMonitorUrl(CommandSender sender, PacketMonitorService monitor) {
        if (!monitor.isRunning()) {
            sendPacketMonitorStatus(sender, monitor);
            return;
        }
        TextComponent line = new TextComponent("     -- ");
        line.setColor(ChatColor.YELLOW);
        TextComponent title = new TextComponent("Panel: ");
        title.setColor(ChatColor.GREEN);
        line.addExtra(title);
        for (String url : monitor.getUrls()) {
            String label = "Suggest panel URL";
            TextComponent link = new TextComponent(label);
            link.setColor(ChatColor.YELLOW);
            link.setUnderlined(true);
            link.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, url));
            link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(label + " with embedded access token").color(ChatColor.GREEN).create()));
            line.addExtra(link);
            break;
        }
        if (sender instanceof Player) {
            ((Player) sender).spigot().sendMessage(line);
        } else {
            sender.sendMessage("Panel URL: " + monitor.getUrl());
        }
    }

    @Command(
            aliases = { "citizens" },
            usage = "reload",
            desc = "",
            modifiers = { "reload", "load" },
            min = 1,
            max = 1,
            permission = "citizens.admin")
    public void reload(CommandContext args, CommandSender sender, NPC npc) throws CommandException {
        if (Setting.WARN_ON_RELOAD.asBoolean()) {
            Long timeout = reloadTimeouts.get(sender);
            if (timeout == null || System.currentTimeMillis() > timeout) {
                Messaging.sendErrorTr(sender, Messages.CITIZENS_RELOAD_WARNING);
                reloadTimeouts.put(sender, System.currentTimeMillis() + 5000);
                return;
            }
        }
        Messaging.sendTr(sender, Messages.CITIZENS_RELOADING);
        try {
            plugin.reload();
            Messaging.sendTr(sender, Messages.CITIZENS_RELOADED);
        } catch (NPCLoadException ex) {
            ex.printStackTrace();
            throw new CommandException(Messages.CITIZENS_RELOAD_ERROR);
        }
    }

    @Command(
            aliases = { "citizens" },
            usage = "save (-a)",
            desc = "",
            modifiers = { "save" },
            min = 1,
            max = 1,
            flags = "a",
            permission = "citizens.admin")
    public void save(CommandContext args, CommandSender sender, NPC npc) {
        Messaging.sendTr(sender, Messages.CITIZENS_SAVING);
        plugin.storeNPCs(args.hasFlag('a'));
        Messaging.sendTr(sender, Messages.CITIZENS_SAVED);
    }
}
