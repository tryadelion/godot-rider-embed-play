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

### 2. Signing key (once)

Marketplace only accepts signed plugins. You sign with your own key and a self-made certificate; nothing
has to be registered with JetBrains. Marketplace checks the signature and then re-signs the plugin with its
own key.

In a terminal (the folder lives in your home directory, outside the repo):

```sh
mkdir -p ~/.sleepyfant-signing && cd ~/.sleepyfant-signing

# 1. Private key, protected by a password you choose (asked twice). Remember it.
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096

# 2. The same key in the form the signer reads (asks for the password).
openssl rsa -in private_encrypted.pem -out private.pem

# 3. The certificate, valid 10 years. It asks for country, name, etc.:
#    "Sleepyfant Software" as Organization Name is enough; the rest may stay empty (type a dot).
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt

chmod 600 ~/.sleepyfant-signing/*
```

Back up the whole `~/.sleepyfant-signing` folder and the password, for example in a password manager.
Every future release must be signed with the same key.

Signing then is one command from the repo root. It asks for the password and produces the signed zip:

```sh
scripts/sign-plugin.sh
# -> build/distributions/godot-embed-play-<version>-signed.zip
```

For CI, the build also accepts the file *contents* in `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and
`PRIVATE_KEY_PASSWORD`.

### 3. Decide before the first upload

- **License:** decided. Apache License 2.0 (`LICENSE`, credit in `NOTICE`); pick *Apache 2.0* in the upload
  form and link https://github.com/tryadelion/godot-rider-embed-play/blob/main/LICENSE.
- **Source code URL:** https://github.com/tryadelion/godot-rider-embed-play
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
   scripts/sign-plugin.sh
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
