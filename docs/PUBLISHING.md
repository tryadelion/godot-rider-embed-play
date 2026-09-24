# Publishing Godot Embed Play to JetBrains Marketplace

For the maintainer (Sleepyfant Software). One-time setup first, then the steps for every release.

## What the build already provides

| Listing element | Comes from |
|---|---|
| Plugin ID | `com.sleepyfant.godot-embed-play`, fixed forever once published |
| Name, vendor, version | `intellijPlatform.pluginConfiguration` in `build.gradle.kts` |
| Description | `<description>` in `src/main/resources/META-INF/plugin.xml` |
| "What's new" | `changeNotes` in `build.gradle.kts` |
| Icon | `src/main/resources/META-INF/pluginIcon.svg` (the Sleepyfant Godot logo) |
| Compatibility | since build 252 (2025.2), no upper bound |

## One-time setup

### 1. Vendor profile

1. Sign in at <https://plugins.jetbrains.com> with a JetBrains Account.
2. Open your profile and **create a vendor**. Choose an *organization* vendor named **Sleepyfant Software**.
3. Fill in the public contact details: website and support e-mail. Marketplace shows them on the plugin page.
4. Optional: add the team members who may upload releases.

The `<vendor>` tag in `plugin.xml` must match this vendor name exactly: `Sleepyfant Software`.

### 2. Signing key

Marketplace requires signed plugins. Create a key and a certificate once, and keep both **outside the repo**:

```sh
mkdir -p ~/.sleepyfant-signing && cd ~/.sleepyfant-signing
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

The build reads them only from environment variables; nothing secret goes into the repo:

| Variable | Value |
|---|---|
| `CERTIFICATE_CHAIN` | contents of `chain.crt` |
| `PRIVATE_KEY` | contents of `private.pem` |
| `PRIVATE_KEY_PASSWORD` | the password chosen above |

Back them up somewhere safe, such as a password manager. All future releases must be signed with the same key.

### 3. Decide before the first upload

- **License:** decided. Apache License 2.0 (`LICENSE`, credit in `NOTICE`); pick *Apache 2.0* in the upload
  form and link the LICENSE file once the repo is public.
- **Source code URL.** Optional, but it builds trust. The repo has no remote yet.
- **Name check.** "Godot" is a trademark of the Godot Foundation. Read its trademark policy
  (<https://godot.foundation>) and confirm "Godot Embed Play" and the logo, which is based on the Godot
  logo, are fine to publish. The README already carries the attribution and a non-affiliation note.
  JetBrains reviewers may ask about third-party names too.
- **Screenshots:** ready in `docs/images/screenshots/`. Upload `play-preview-with-code.png` (first),
  `play-preview-running.png` and `play-preview-idle.png` on the listing page after the first upload.

## Every release

1. Bump `version` in `build.gradle.kts` and add an entry at the top of `changeNotes`.
2. Check compatibility with the IDEs Marketplace recommends. This downloads several IDEs the first time:

   ```sh
   ./gradlew verifyPlugin
   ```

3. Build and sign:

   ```sh
   export CERTIFICATE_CHAIN="$(cat ~/.sleepyfant-signing/chain.crt)"
   export PRIVATE_KEY="$(cat ~/.sleepyfant-signing/private.pem)"
   export PRIVATE_KEY_PASSWORD='…'
   ./gradlew signPlugin
   # -> build/distributions/godot-embed-play-<version>-signed.zip
   ```

4. Upload:
   - **First release only, by hand:** on <https://plugins.jetbrains.com>, choose **Upload plugin**, pick the
     *signed* zip, select the Sleepyfant Software vendor, license, category (*Tools Integration*, tags
     *Godot*, *Game Development*) and the *Stable* channel.
   - **Later releases:** create a token under *My Tokens* on Marketplace, then:

     ```sh
     export PUBLISH_TOKEN='…'
     ./gradlew publishPlugin
     ```

5. **Review.** JetBrains reviews the first upload and new vendors by hand. That usually takes a few working
   days, and they e-mail questions to the vendor contact. Later updates are usually approved faster.

## After publishing

- Put the Marketplace link and install button in the README.
- Users report problems through the listing's review section or the vendor e-mail. Add an issue tracker URL
  in the plugin settings on Marketplace once the repo is public.
