# ClaimoDiscordAddon

A [Claimo](https://claimo.vao.zone) addon that adds Discord requirements to voucher codes.
Players link their Discord account once, and after that a code can require things like being in
your server, holding a role, boosting, sending a number of messages, using a slash command,
wearing your server tag, or having a set status text.

It talks to Discord through your own bot (JDA), so you only need a bot token and your server id.

## Requirements

- Claimo installed on the server. This is an addon and will not load without it.
- A Discord bot token from the [Discord Developer Portal](https://discord.com/developers/applications).
- A Paper or Folia server on 1.21.1 or newer (tested up to 26.2). Folia is supported.

## Requirement types

Use these `type` values inside a voucher's `requirements` list.

| Type | What it checks | Options |
| --- | --- | --- |
| `discord_linked` | The player has linked a Discord account | none |
| `discord_member` | The account is in your Discord server | none |
| `discord_role` | The account has (or does not have) certain roles | `roles`, `denied-roles` |
| `discord_booster` | The account is boosting your server | none |
| `discord_messages` | The account has sent enough messages | `amount` |
| `discord_command` | The account has used a slash command enough times | `command`, `amount`, `description` |
| `discord_server_tag` | The account wears your server tag | `tag` (optional) |
| `discord_status` | The account's custom status contains or equals a value | `value`, `match` |

Notes on options:

- `roles` and `denied-roles` take role ids or names, either as a list or a comma separated
  string. The player passes when they hold at least one of `roles` and none of `denied-roles`.
- `command` is the name of a slash command. That command is only registered on your server when
  at least one voucher uses it, so nothing extra shows up in Discord until you set it.
- `tag` is optional. Leave it out to accept any tag that points at your server, or set it to
  require an exact tag text.
- `match` is `contains` (default) or `equals`.

## Setup

1. Create an application in the Discord Developer Portal and add a bot to it. Copy the bot token.
2. Invite the bot to your server. Use the `bot` and `applications.commands` scopes.
3. Turn on Developer Mode in Discord, right click your server, and copy the server id.
4. Drop the addon jar in `plugins/` next to Claimo and start the server once so the config is written.
5. Open `plugins/ClaimoDiscordAddon/config.yml`, paste the token into `bot.token` and the id into
   `bot.guild-id`, then restart.

### Privileged intents

Some checks need privileged intents, which you enable in the Developer Portal under
Bot, Privileged Gateway Intents, and then mirror in the config under `bot.intents`:

- `discord_status` needs the Presence intent (`intents.presences: true`).
- `intents.members: true` is optional. It caches members for faster role and member checks. When
  it is off, those checks still work through direct lookups.

Message counting uses a normal intent that is on by default. The bot does not read message
content, it only counts messages per author.

## Linking an account

Linking uses a panel message with a button, so players never need a slash command.

1. As an admin, post the panel once with `/claimodiscord panel <channel-id>` (or set a default
   channel under `discord.panel-channel-id` and run `/claimodiscord panel`). The bot sends an embed
   with a Link account button to that channel.
2. A player runs `/claimodiscord link` in game and gets a short code.
3. They click the button on the panel, paste the code in the popup, and submit.
4. The bot links the two accounts, replies with a confirmation embed, and tells the player in game.

The button and popup keep working after a bot restart. Discord sends the button id with every
click, so the bot does not need the old panel message in its cache to handle it.

A code expires after a few minutes (set by `link.code-expiry-seconds`). Players can unlink at any
time with `/claimodiscord unlink`.

## Using it in a voucher

Add the requirements to a voucher file in `plugins/Claimo/vouchers/`. Example:

```yaml
cmd: "lp user %player% parent addtemp vip 7d"
console: true
requirements:
  - type: discord_member
  - type: discord_role
    roles: "Supporter, 1234567890"
  - type: discord_messages
    amount: 50
  - type: discord_command
    command: daily
    amount: 1
    description: "Claim your daily reward"
  - type: discord_server_tag
  - type: discord_status
    value: "play.myserver.net"
    match: contains
```

On servers running the in game code creator, these types also show up there with input fields,
so you can build them without editing files.

## Storage

Links and counters are saved through one storage layer. Pick the backend in `config.yml` under
`storage.type`:

- `yaml` writes to `links.yml`. No setup, single server.
- `sqlite` writes to `links.db`. No setup, single server.
- `mysql`, `mariadb`, `postgresql` use a shared database, which is useful across a network.

The SQL drivers are downloaded by the server at first start, so the first launch needs an internet
connection. Changing the storage type needs a restart.

## Commands and permissions

| Command | Permission | Description |
| --- | --- | --- |
| `/claimodiscord link` | `claimodiscord.link` | Get a code to link your account |
| `/claimodiscord unlink` | `claimodiscord.link` | Remove your link |
| `/claimodiscord list` | `claimodiscord.link` | Show your linked account |
| `/claimodiscord status` | `claimodiscord.admin` | Show the bot state and server id |
| `/claimodiscord panel [channel-id]` | `claimodiscord.admin` | Post the link panel with the button |
| `/claimodiscord set <player> <discord-id>` | `claimodiscord.admin` | Link a player by hand |
| `/claimodiscord reload` | `claimodiscord.admin` | Reload messages and re-sync slash commands |

`claimodiscord.link` defaults to everyone. `claimodiscord.admin` defaults to operators.

Changes to the bot token, server id, intents, or storage type need a full restart. Message and
slash command changes apply on reload.

## Building

```bash
./gradlew build
```

The build produces `build/libs/ClaimoDiscordAddon-v<version>.jar`. That is the file to install.

The addon compiles against `claimo-api`, which comes from the vao repository. JDA and its helper
libraries are shaded into the jar under a relocated package so they do not clash with other
plugins. The SQL drivers and HikariCP are declared as plugin libraries and pulled in at runtime.

## Notes

- Message counts start from the moment the bot connects. Discord does not expose a past total, so
  older messages are not counted.
- The server tag check reads the account's profile from the Discord API, so it reflects the tag
  the player currently wears.
- If the bot is offline or has no token set, the Discord requirements report as not met instead of
  failing the whole code.
