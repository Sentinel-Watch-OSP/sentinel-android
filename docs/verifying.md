# Verifying a Sentinel release

A watch-only wallet cannot lose your coins, but a tampered one can show you a
receive address that is not yours. Checking a download takes a few minutes and
is worth doing at least for the first install; after that, Android itself
refuses any upgrade not signed with the same certificate.

There are two independent checks, and they answer different questions:

1. **The PGP signature on `SHA256SUMS`** proves the maintainers published this
   exact file. It is checked on a computer, before installing.
2. **The APK signing certificate** proves the app was signed with the same key
   as every earlier Sentinel. It can be checked on a computer with `apksigner`
   or on the phone with AppVerifier.

Neither proves the APK was built from the published source. The builds are not
yet reproducible; that is a known gap, not an oversight.

## What you need

From the [release page](https://github.com/Sentinel-Watch-OSP/sentinel-android/releases/latest),
download all three files into one folder:

- `Sentinel-vX.Y.Z.apk`
- `SHA256SUMS`
- `SHA256SUMS.asc`

You also need GnuPG (`gpg`). On Linux it is usually installed already; on macOS
use GPG Suite or `brew install gnupg`; on Windows use Gpg4win and run the
commands in its command prompt.

## 1. Get the key and check its fingerprint

The key is not stored in this repository or on the release page. Fetch it from
the public keyserver, so the key and the files it signs come from different
places:

```
gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys 01FEE827374CDE450791DC311077741E06A8C385
```

If that fails (some networks block the keyserver port), download it over HTTPS
instead. The same link works in a browser:

```
curl -o sentinel-release.asc "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x01FEE827374CDE450791DC311077741E06A8C385"
gpg --import sentinel-release.asc
```

Then check the fingerprint:

```
gpg --fingerprint 01FEE827374CDE450791DC311077741E06A8C385
```

The output must show this fingerprint, character for character:

```
01FE E827 374C DE45 0791 DC31 1077 741E 06A8 C385
```

Compare it with the copy on <https://sentinel.watch> and in this repository's
README. If an attacker had replaced the key on the keyserver, they would have
to replace all of those too.

This is wanderingking072's key. Its user ID is `WanderingKing072`, with no
email address. An older user ID with a `samourai.is` email also shows, marked
as revoked; that is expected. It is still the right key, the same one that signed V5.1.0, V5.1.1, V5.1.1a and
V5.1.2. **The fingerprint is what you check**; a user ID is only a label that
anyone can put on any key.

## 2. Check the signature

```
gpg --verify SHA256SUMS.asc SHA256SUMS
```

You want a line reading:

```
gpg: Good signature from "WanderingKing072"
```

with the fingerprint above. GnuPG will also print:

```
gpg: WARNING: This key is not certified with a trusted signature!
```

That warning is normal. It means you have not personally signed the key in
your keyring, not that anything is wrong. Checking the fingerprint in step 1 is
what replaces that.

`BAD signature`, or a signature from any other key, means stop: do not install
the APK, and please report it as [SECURITY.md](../SECURITY.md) describes.

## 3. Check the APK against the signed hash

```
sha256sum -c SHA256SUMS
```

On macOS, use `shasum -a 256 -c SHA256SUMS`. On Windows PowerShell, run
`Get-FileHash Sentinel-vX.Y.Z.apk` and compare the hash with the one inside
`SHA256SUMS` by eye.

You want:

```
Sentinel-vX.Y.Z.apk: OK
```

`FAILED` means the APK you have is not the one that was signed. Download it
again; if it still fails, do not install it.

## 4. Check the APK signing certificate (optional, recommended)

Every Android app is signed with a certificate, and Android will only upgrade
an app with one signed by the same certificate. Checking it once tells you
this is the same Sentinel line you may already have installed.

Every official release since V5.1.0 (V5.1.0, V5.1.1, V5.1.1a and V5.1.2,
checked with `apksigner`) has one signer, with this certificate:

| | |
|---|---|
| Subject | `CN=Wandering King 072` |
| Certificate SHA-256 | `79655c78081589e256d098f737d32b54788bff1ad1d8f66b131a6a5316d21894` |
| Public key SHA-256 | `d9b851b9ac9ceb4f043e6a3ba90a7217e7552c1a3e996b65735b7f480d86de0e` |
| Signature schemes | v1 (JAR) and v2 |

The **certificate SHA-256** is the value to compare. It is the one Obtainium
and AppVerifier use. The same value in the colon-separated form AppVerifier
displays:

```
79:65:5C:78:08:15:89:E2:56:D0:98:F7:37:D3:2B:54:78:8B:FF:1A:D1:D8:F6:6B:13:1A:6A:53:16:D2:18:94
```

The public key SHA-256 is given for completeness; it identifies the key inside
the certificate, and some tools show it instead.

### On a computer, with apksigner

`apksigner` ships with the Android SDK build tools
(`$ANDROID_HOME/build-tools/<version>/apksigner`).

```
apksigner verify --verbose --print-certs Sentinel-vX.Y.Z.apk
```

For a genuine release the output includes these lines (others, such as the
SHA-1 and MD5 digests, are left out here):

```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: CN=Wandering King 072
Signer #1 certificate SHA-256 digest: 79655c78081589e256d098f737d32b54788bff1ad1d8f66b131a6a5316d21894
Signer #1 public key SHA-256 digest: d9b851b9ac9ceb4f043e6a3ba90a7217e7552c1a3e996b65735b7f480d86de0e
```

The line that matters is `Signer #1 certificate SHA-256 digest:`. It must
match the value above exactly, and there must be exactly one signer. Leave
out `--verbose` and you still get the certificate lines, just not the scheme
lines. Older build tools may not print the public key line; that is fine.

### On the phone, with AppVerifier

[AppVerifier](https://github.com/soupslurpr/AppVerifier) checks an installed
app's certificate against a value you paste in. After installing Sentinel,
copy the package name and hash below, open AppVerifier, and use "Verify from
clipboard":

```
com.samourai.sentinel
79:65:5C:78:08:15:89:E2:56:D0:98:F7:37:D3:2B:54:78:8B:FF:1A:D1:D8:F6:6B:13:1A:6A:53:16:D2:18:94
```

AppVerifier should report the app as verified. If it shows a different hash,
the installed app was not signed by this project.

Obtainium users can rely on Android's own check from then on: an update signed
with a different certificate will fail to install.

### Samourai-era builds use a different certificate

Builds from before V5.1.0 were signed by Samourai, with a key this project does
not have. Their certificate will not match the one above, and Android will
refuse to install a current release over one of them. That is expected, and it
is the one case where you uninstall first: export a backup, uninstall, install
the verified new APK, and import the backup.

From V5.1.0 onwards, every release has the same certificate. If one does not,
do not install it.

## Older releases

V5.1.0 to V5.1.2 were first published with a separate signed message in the
now-archived `APKs-Sentinel` repository. Their release pages have since been
given `SHA256SUMS` and `SHA256SUMS.asc` like every newer release, so the steps
above work for them too. The APK file names on those releases keep their
original names (for example `Sentinel_V5.1.2_production.apk`), because renaming
a published file would break existing download links and Obtainium sources.
