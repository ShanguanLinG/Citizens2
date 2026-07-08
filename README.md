Citizens2 README
================

Citizens is an NPC plugin for the Bukkit API. It was first released on March 5, 2011, and has since seen numerous updates. Citizens provides an API which developers can use to create their own NPC characters. More information on the API can be found on the API page of the Citizens Wiki (https://wiki.citizensnpcs.co/API).

Compatible with:
* Minecraft (for specific compatible version information, see https://wiki.citizensnpcs.co/Versions for info)
* CitizensAPI (for compiling purposes only)

This branch
===========

This branch is a patched 1.8.8-compatible Citizens build focused on reducing
NPC packet pressure on servers with many player-type NPCs, frequent NPC deaths,
large combat areas, and many simultaneous viewers.

The extra options are stored in `plugins/Citizens/patched-config.yml` so the
upstream Citizens configuration stays easy to compare.

Packet and visibility goals:

* Reduce unnecessary NPC tracking for players who should not need live bot
  updates.
* Smooth out large visibility changes so bandwidth spikes are spread across
  multiple ticks.
* Reduce repeated `PLAYER_INFO` traffic for player-type NPCs without removing
  the packets that the vanilla 1.8 client still needs for GameProfile and skin
  data.
* Provide a built-in packet monitor for short diagnostic sessions.

NPC visibility and tracking optimizations:

* NPCs can be hidden per viewer when the viewer is above a configured Y level,
  outside a configured distance, idle for a configured amount of time, outside
  the likely field of view, or blocked by line of sight checks.
* Hidden NPCs are removed from that viewer's tracking set, reducing ongoing
  movement, look, head rotation, velocity, metadata, animation, and related
  entity update packets.
* `npc.visibility.smooth-reveal.enabled` applies to both directions of a
  visibility change. It smooths hidden-to-visible reveals and also
  visible-to-hidden untracking, so large groups of NPCs do not all spawn or
  despawn for a player in the same tick.
* `npc.visibility.smooth-reveal.max-per-player-per-tick` limits how many NPC
  visibility changes one player can process per tick. Lower values produce
  smoother bandwidth at the cost of slightly slower reveal/hide transitions.
* Player activity tracking now treats damaging an NPC as activity. A player who
  is attacking NPCs is not considered idle only because their camera stays still.
* Optional display-name and tab-list-name hiding can be enabled for selected NPC
  names to reduce unnecessary client-side display work.

Player-type NPC `PLAYER_INFO` optimizations:

* Repeated `PLAYER_INFO ADD_PLAYER` packets are suppressed while the same
  viewer/NPC pair is still inside the delayed tab-list remove window.
* `TabListAdder` batches multiple `PLAYER_INFO ADD_PLAYER` entries for the same
  receiving player into one packet. This reduces packet count and packet header
  overhead when many player-type NPCs become visible together.
* The existing tab-list remove batching is preserved and coordinated with the
  new add batching, keeping add/remove behavior smoother for player-type NPCs.
* `PLAYER_INFO` is not removed entirely. The 1.8 client still needs it before
  spawning a player-type NPC so the client can receive the GameProfile and skin
  properties.
* Initial NPC link/spawn paths remain conservative; the more aggressive batching
  is applied to skin refresh and visibility update paths where a one-tick batch
  window is safer.

Movement and death-state optimizations:

* `npc.performance.movement-update-multiplier` can spread player-type NPC
  movement updates across ticks. Values below `1.0` reduce packet and CPU
  pressure at the cost of slightly less fluid movement.
* The human NPC fast respawn path reduces the chance that observers keep seeing
  a frequently killed NPC stuck in the death/falling animation.

Skin caching:

* Successfully fetched skin profiles are cached locally in `skin-cache.yml`.
* On later starts, the cached texture/signature can be applied immediately while
  a fresh remote lookup is attempted in the background when needed.
* This mainly reduces Mojang/Geyser HTTP requests and avoids temporary missing
  skins after restarts or remote rate limits. It does not remove the need to send
  required `PLAYER_INFO` data to clients.

Packet monitor:

* `/citizens packetmonitor` starts a temporary local web dashboard for Citizens
  NPC packet diagnostics.
* The dashboard shows packet rate, estimated bandwidth, active NPC count,
  receiving players, packet-type breakdowns, and per-player distribution.
* Bandwidth units are automatically scaled on the web UI, making KiB/s and
  MiB/s level changes easier to read.
* `packet-monitor.display-host` controls the host shown in generated links. The
  monitor service itself keeps its local binding behavior.
* The monitor is intended for measurement windows, not as a permanently exposed
  public service. Keep the generated token private.

Extra information
=================

Javadoc: http://jd.citizensnpcs.co

Spigot page: https://www.spigotmc.org/resources/citizens.13811

Developmental builds: https://ci.citizensnpcs.co/job/Citizens2/

For questions/help join our discord at: https://discord.gg/Q6pZGSR
