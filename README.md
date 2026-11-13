# mm-tg

A Mattermost client for Android built on the Telegram Android UI.

The Telegram client's MTProto networking layer has been replaced by a Mattermost backend
(`TMessagesProj/src/main/java/org/telegram/mattermost/`). It talks to a Mattermost server's REST API v4
and websocket, and nothing connects to Telegram's servers.

| Mattermost | shown as |
|---|---|
| 1:1 direct message | private chat |
| direct message to yourself | Saved Messages |
| public / private channel, group message | group |
| thread reply (`root_id`) | reply |
| file attachment | photo / document |

The server is entered on the sign-in screen (username/email + password, MFA supported).

## Building

GitHub Actions builds `mm-tg.apk` (arm64) on every push to `main` and publishes it as the `latest` release.
The native libraries are built once and cached. Java-only changes build in a few minutes.

Local Java-only build, using the native libs from the CI `native-libs` artifact:

    git submodule update --init --depth 1 TMessagesProj_Modules/media TMessagesProj/lib/jlatexmath
    ./gradlew :TMessagesProj_App:assembleAfatDebug -PprebuiltNative=/path/to/native-libs

## License

This is a fork of [Telegram for Android](https://github.com/DrKLO/Telegram) and, like the original,
is licensed under the GNU GPL v2 (see `LICENSE`). It is not affiliated with Telegram or Mattermost.
