# Security policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.** Sentinel shows
people their Bitcoin balances and addresses, so a bug that leaks an XPUB, an
address or a Dojo pairing can tie a person to their coins. That is worth
keeping quiet until it is fixed.

The preferred route is GitHub's **[private vulnerability
reporting](https://github.com/Sentinel-Watch-OSP/sentinel-android/security/advisories/new)**.
It is private between you and the maintainers, needs no email address from
either side, and keeps the report attached to the repository.

If you would rather not use GitHub, or want to encrypt the report, the
release-signing OpenPGP key is:

```
01FE E827 374C DE45 0791 DC31 1077 741E 06A8 C385
```

This is wanderingking072's key, published on the
[Ubuntu keyserver](https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x01FEE827374CDE450791DC311077741E06A8C385). Its user ID is `WanderingKing072`, with no email;
an older `samourai.is` user ID shows as revoked, which is expected. The
fingerprint above is what identifies the key. Use private vulnerability reporting, and if
you want encryption, say so in the report and you will be sent a working
address to encrypt to. Unencrypted email about a vulnerability is not welcome,
which is why none is published here.

Please include what you would want to receive yourself: what the problem is,
how to reproduce it, what an attacker gains, the Sentinel version (Settings →
About) and the Android version and device you saw it on.

## What is in scope

Sentinel is watch-only: it never holds private keys. The most valuable things
to attack are therefore what it knows about you and what it tells you:

- **Leaking what you watch.** Anything that sends an XPUB, address, label or
  balance anywhere the user did not choose, including outside Tor when Tor is
  enabled, or to a server other than the paired Dojo.
- **Dojo pairing.** Anything that lets a pairing payload, a Dojo API key or an
  onion address be read by another app, logged, or accepted from a source the
  user did not scan or paste.
- **Showing the wrong thing.** Anything that makes Sentinel display an address
  to receive into that does not belong to the watched wallet, or a balance or
  transaction that is not real.
- **Local data.** Bypassing the PIN, reading the app's storage or an exported
  backup without its passphrase, or a backup import that runs or overwrites
  more than it should.
- **The release chain.** Anything that gets a user to install an APK that was
  not built and signed by the maintainers: a flaw in the published checksums,
  signatures or instructions in [`docs/verifying.md`](docs/verifying.md).

## What is not

- **A Dojo behaving badly**, including a community Dojo listed on Dojo Bay, is
  not a vulnerability in Sentinel. Sentinel trusts the Dojo you pair it with to
  report the chain;
  that is the trust model, and the README says so.
- **Anything that needs a rooted or already compromised phone**, or physical
  access to an unlocked one.
- **Old Samourai infrastructure.** Samourai's servers, domains and keys are
  outside this project's control. If you find Sentinel still relying on one,
  that is welcome as an ordinary issue.
- **Missing hardening with no practical attack**, which is welcome as an
  ordinary issue rather than an advisory.

## What to expect

Sentinel is maintained by a small volunteer team, so the honest commitment is
a first reply within a week rather than a same-day acknowledgement. If a report
is valid, you should expect agreement on what the problem is, a fix in a new
release, and credit in the release notes unless you would rather not be named.

## Supported versions

The latest release on [GitHub
Releases](https://github.com/Sentinel-Watch-OSP/sentinel-android/releases/latest) is the
supported one. There are no long-term support branches; fixes go into the next
release, and Obtainium will offer it.
