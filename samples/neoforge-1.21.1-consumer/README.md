# MCWebUI NeoForge consumer sample

This fixture is an independent NeoForge 1.21.1 Gradle build. It resolves the
MCWebUI Developer Preview from a staged binary Maven repository; it does not
depend on the MCWebUI source project, `common` output, or target classes.

From the repository root, run:

```text
gradlew.bat verifyConsumerSample
```

The root task builds the MCWebUI NeoForge JAR, stages
`dev.qingmo.mcwebui:mcwebui-neoforge-1.21.1:0.1.0-SNAPSHOT` under
`build/consumer-repo`, and then invokes this fixture with a clean build.

The sample only imports `dev.qingmo.mcwebui.api.*`,
`dev.qingmo.mcwebui.api.neoforge.*`, `dev.qingmo.mcwebui.resource.*`, and
Minecraft/NeoForge APIs. Press F9 in a client run to open
`sample:control-panel`.
