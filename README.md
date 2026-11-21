# Discovery

A Fabric client mod that adds a **Discovery** page to Minecraft's title screen: Java servers
ranked by [serverlist.cc](https://serverlist.cc)'s live tracker, with search, genre rows, live
player counts and one-click join. Think Bedrock's server tab, or Roblox's front page, for Java.

Made by [Kalpe Games](https://kalpe.games). Download it from
[Modrinth](https://modrinth.com/mod/discovery).

## Features

- **Discovery page** on the title screen, the pause menu and the Multiplayer screen.
- **Top servers, Rising and genre rows**, with art, live counts and ping from your own PC.
- **Search and Sort & filter**: by players, version, growth, newest; hide full or empty servers.
- **Server pages** with links, a description and a player-count chart.
- **Jump back in** and **Favourites**, kept on your PC.
- **Friends**: see which listed server your friends are on, and join them.
- **Side padding** and other layout settings.
- **Updates** itself from Modrinth when you click Update (installed when the game closes).

## Versions

One jar per Minecraft version, Fabric only. Fabric API is not needed.

| Minecraft | Java |
|---|---|
| 1.21.8, 1.21.9-1.21.10, 1.21.11 | 21 |
| 26.1.x, 26.2, 26.3 | 25 |

## Privacy

Discovery shares **play stats** with serverlist.cc, on by default: which listed servers you join
and for how long, under your Minecraft username. It is how servers get ranked by how people
actually play. The first time you open the page a notice says so, with **Turn off** right there;
the switch is also in Settings. Turning it off asks serverlist.cc to delete what it holds for you.

- Your identity is proven the way a server login does it, through Mojang. Your Minecraft access
  token never leaves your PC except to Mojang's own session server.
- Only servers listed on serverlist.cc are reported. The address of a server that isn't listed
  is never sent; the mod checks addresses against serverlist.cc's public list on your PC.
- With the stats go how the Discovery page was used (what was opened, searched as a length, and
  joined) and basic client details: Minecraft and mod version, operating system, language,
  window size and how many mods are installed.
- Chat, worlds, coordinates, kick messages and anything else in game are never collected.
- Friends see which listed server you're on, unless you set "Show where I'm playing" to Nobody
  or appear offline.

Full policy: https://serverlist.cc/privacy

## Building

Requires a JDK 25 for Gradle itself (the 1.21.x targets still compile for Java 21).

```
./gradlew releaseJars     # every version -> build/release/
./gradlew :1.21.8:build   # one version; the nodes are 1.21.8, 1.21.9, 1.21.11, 26.1.2, 26.2, 26.3
```

One source tree serves every version through [Stonecutter](https://stonecutter.kikugie.dev).
The source is written against 26.3's Mojang names. For older versions:

- `build.gradle.kts` holds word-bounded renames between versions (for example
  `GuiGraphicsExtractor` / `GuiGraphics`, `Identifier` / `ResourceLocation`). Stonecutter runs each
  rename backwards on the versions it doesn't apply to, so never add a bare string swap there.
- Calls that differ in shape live in `src/mc<version>/java/.../compat/Compat.java`.
- The rest are `//? if >=1.21.11 {` blocks.

1.21.x builds use Loom's remap plugin with Mojang's mappings, so names match 26.x.

For a test run, `-Dserverlist.autoOpen=true` opens the page at the title screen, and an empty
file `config/serverlist-mixin-audit` makes the mod apply every mixin at once, so a hook that
doesn't fit a version fails immediately.

## Contributing

Bug reports and pull requests are welcome. Please read the license first: contributions are
licensed to Kalpe Games (section 5).

## License

Source-available under the [Discovery License](LICENSE). In short:

- **You can** read and build the source, and include the official, unmodified jar in modpacks,
  launchers and clients, free or paid.
- **You can't** publish modified versions, change its look or branding, point it at another
  backend, or reuse its code in other projects.
- Packs must not hide or pre-dismiss the play stats notice.

This summary isn't the license; [LICENSE](LICENSE) is.
