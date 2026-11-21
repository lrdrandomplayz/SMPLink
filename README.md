# SMPLink

A Paper plugin that links your players' Discord accounts to their Minecraft accounts and runs a Discord bot for
your SMP. The bot is built into the plugin, so there's no separate bot to host.

- **Account linking.** A player runs `/link` in Minecraft, then enters the code with `/link` on Discord.
  Links can also come from a Google Sheet instead.
- **Profiles and playtime.** `/profile`, `/playtime` and `/leaderboard` (all time, monthly, weekly), using vanilla
  stats plus SMPLink's own session tracking.
- **Activity checks.** A weekly playtime quota. Members under it get a DM with a deadline, then a strike if they miss
  it. Strikes expire, and reaching the limit removes the member. `/activitycheck`, `/strike`, `/warnings`, `/lookup`.
- **Milestones and recaps.** Announcements at 24/50/100/200 hours (with optional roles), and a weekly top-players post.
- **Join applications.** An "Apply to join" panel. Staff get a card with Accept (whitelists them), Deny and
  Ask a question.
- **Trade board.** `/trade` posts offers with item pictures. It has an "I'm interested" button with private
  negotiation threads, optional middlemen, and vouch/report reputation.
- **Crash recovery.** Player snapshots every few seconds. After a crash, staff get restore cards for anyone who lost
  items. There's also a health monitor that alerts on low memory or TPS.
- **Join lock.** `/joinlock` keeps everyone but staff out until an opening time.
- `/inv` shows a linked player's inventory, armor and ender chest as a picture.

## Requirements

- Paper 1.21.1 or newer, Java 21+
- A Discord bot ([Developer Portal](https://discord.com/developers/applications)). Slash commands need no
  privileged intents.
- Optional: CoreProtect (crash recovery uses it to spot duplicated items)

## Setup

1. Put `SMPLink.jar` in `plugins/` and start the server once. This creates `plugins/SMPLink/config.yml`.
2. Create a bot in the Discord Developer Portal. Copy its token into `discord.token`, and your server's ID into
   `discord.guild-id`.
3. Invite the bot with the `bot` and `applications.commands` scopes. It needs Send Messages, Embed Links,
   Attach Files, Manage Roles (for linked and milestone roles), Manage Nicknames (if `nickname-sync` is on), and
   Create Private Threads (for trades and applications).
4. Set `discord.admin-role-id` to your staff role, set `server.name`, and fill in the channel IDs for the features
   you want. Every feature with an empty channel is off.
5. Restart. The bot registers its slash commands in your server.

### Item icons

Minecraft's textures can't be shipped in an open-source jar. Put item pictures in `plugins/SMPLink/icons/`,
named after the item ID (`diamond_sword.png`, `oak_log.png`, ...). Any icon pack or your own resource pack
works. Items without an icon show the missing-texture square.

### Linking with a Google Sheet

Set `linking.mode: sheet` and fill in `sheet.id`. Then create a Google Cloud service account, save its key as
`plugins/SMPLink/service-account.json`, and share the sheet with the service account's email (as Editor if you
want `/whitelist` to add rows). The sheet needs the columns `MC Username` and `Discord`. Links sync every
`sheet.sync-interval-minutes`, and `/smplink sync` runs one now.

## Commands

| Discord | Who | What |
|---|---|---|
| `/link code:` · `/unlink` | everyone | Link or unlink your account |
| `/profile` · `/playtime` · `/leaderboard` | everyone | Stats, quota progress, rankings |
| `/trade` · `/trades` | linked members | Post or search trade offers |
| `/whitelist add/remove` · `/lookup` · `/warnings` | whitelist role, admins | Manage members |
| `/activitycheck run/pause/resume` · `/strike add/remove` · `/inv` · `/sync` · `/joinlock` | admins | Moderation |
| `/applications panel` | admins | Post the apply button |

| Minecraft | Permission | What |
|---|---|---|
| `/link` | `smplink.link` (everyone) | Get a link code |
| `/linkinfo <player>` | `smplink.admin` | Show a player's Discord link |
| `/smplink sync` | `smplink.admin` | Import from the sheet now |
| `/joinlock` | `smplink.admin` | Lock joining until a time |

## Building

```
./gradlew build
```

The plugin jar is `build/libs/SMPLink.jar`. Tests run as part of the build.

## Contributing

Issues and pull requests are welcome. Please run `./gradlew build` before opening a PR, and add a test for
behaviour you change.

## License

[MIT](LICENSE)
