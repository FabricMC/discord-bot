# Thimble

A Discord bot for the official Fabric Discord server, implemented in Java.

The bot is a rewrite of https://github.com/FabricMC/kotlin-fabric-discord-bot which is responsible for the bulk of the initial ideas, features, design choices and user visible appearance.

# Building

The bot requires JDK 16.
Gradle's toolchain logic should install JDK 16 for you if you do not have it installed.

TODO: More description

# Contributions

Please run the `spotlessApply` task to update license headers before submitting any PRs or commits

# Testing

You need to set the token, database URL, guild ID and command prefix to start the bot.
Other configuration settings may be set at runtime using the `config` command. There are no external dependencies outside Discord, the database is self contained in the sqlite-jdbc library.
