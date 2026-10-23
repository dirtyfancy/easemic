<p align="center">
  <img src="docs/logo.svg" alt="Easemic" width="96">
</p>

<h1 align="center">Easemic</h1>

<p align="center">A private manga and doujinshi shelf that streams straight from cloud storage you own: E-Hentai, nhentai, JMComic, Zaimanhua, and self-hosted OPDS in one place.</p>

<p align="center">
  <a href="https://easemic.com">Website</a> ·
  <a href="https://easemic.com/api/app/download">Download APK</a> ·
  <a href="https://github.com/dirtyfancy/easemic/releases">GitHub Releases</a> ·
  <a href="https://easemic.com/#film">25s Film</a> ·
  <a href="https://easemic.com/guides/cloud-reading-speed/">Cloud Speed Benchmarks</a> ·
  <a href="https://easemic.com/guides/cloud-storage-api-key/">Storage Setup</a> ·
  <a href="https://github.com/dirtyfancy/easemic/issues">Issues</a> ·
  <a href="README.md">中文</a>
</p>

<p align="center">
  <a href="https://github.com/dirtyfancy/easemic/releases"><img src="https://img.shields.io/github/v/release/dirtyfancy/easemic?include_prereleases&amp;sort=semver&amp;display_name=tag" alt="release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="license"></a>
  <a href="https://easemic.com/api/app/download"><img src="https://img.shields.io/badge/Android-10%2B%20(API%2029%2B)-3DDC84?logo=android&amp;logoColor=white" alt="Android 10+"></a>
  <a href="https://alternativeto.net/software/easemic/about/"><img src="https://alternativeto.net/static/badges/badge-compact-color.svg" alt="Easemic on AlternativeTo"></a>
  <img src="https://img.shields.io/badge/18%2B-adult%20content-critical" alt="18+ adult content">
</p>

> **18+** Built for adults managing their own manga and gallery collections. This repository contains no media or site data; connecting to external catalogs requires your own accounts and network access.

<p align="center">
  <a href="https://easemic.com/#film"><img src="docs/film-poster.jpg" width="92%" alt="Watch the 25-second Easemic film" /></a>
</p>

## Features

- **Galleries and serialized manga in one app**: browse single-volume gallery catalogs (**E-Hentai**, **ExHentai**, **nhentai**), multi-chapter serialized manga (**JMComic / 禁漫天堂**, **Zaimanhua / 再漫画**), and your own **Kavita / Komga (OPDS)** server side by side.
- **Collect to your cloud, zero phone bloat**: tap the cloud icon on any gallery or chapter to save it straight into **Cloudflare R2**, **Bunny Storage**, **Backblaze B2**, **Google Drive**, **OneDrive**, or **WebDAV** (Nextcloud, Synology NAS, Nutstore, 123pan). Turn on *Keep only the cloud copy* to release local storage right after upload.
- **Stream straight from your bucket**: pages stream on demand in milliseconds (123 ms median first-page open on Bunny Storage, 199 ms on Cloudflare R2) with neighbor prefetching, animated GIF/WebP playback, and an instant first-frame resume cache.
- **Takedown-proof shelf**: once collected, a gallery or manga chapter lives in storage you control. If a gallery disappears upstream or a serialized chapter is replaced or removed, your saved copy stays readable and removed chapters are marked `Removed`.
- **Instant multi-device & new-phone sync**: bind a second phone, a tablet, or a brand-new phone to the same bucket or drive and your entire library, covers, reading progress, and bookmarks appear immediately—no multi-gigabyte folder transfers.
- **Client-side end-to-end encryption**: libraries on Google Drive, OneDrive, and WebDAV are encrypted on your device with your password before upload; on S3-compatible storage (R2, Bunny, B2), encryption is your choice. Keys never leave your phone.
- **Bring your existing `.ehviewer` & CBZ collection**: import local `.ehviewer` download directories, image folders, and `CBZ`/`ZIP` archives (with `ComicInfo.xml`), or import your ExHentai favorites in one tap.
- **Dual-engine webtoon & manga reader**: automatically detects long-strip webtoons/manhwa (`height / width >= 2.15`) for full-width vertical scrolling with **5-speed hands-free auto-scroll** (touch to pause, lift to resume), alongside right-to-left manga paging.
- **Trailing ad-page stripping**: optional perceptual-hash (`dHash`) + semantic tail detection strips repetitive sponsor/gambling ad pages during cloud upload while preserving scanlation credit pages.
- **Background chapter updates**: checks collected serialized series for new chapters and lights up `+N` badges on your shelf.

Reading history, bookmarks, and storage credentials stay on your device and in your own cloud storage. No accounts, no central servers, no analytics SDKs, and no ads.

## Screenshots

Click any screenshot to view the full-resolution image. Watch the 25-second promo film and read cloud benchmarks at [easemic.com](https://easemic.com).

<table>
  <tr>
    <td width="33%"><a href="docs/screenshots/browse-ehentai.jpg"><img src="docs/screenshots/browse-ehentai.jpg" alt="E-Hentai gallery search and language filter" width="360"></a></td>
    <td width="33%"><a href="docs/screenshots/browse-nhentai.jpg"><img src="docs/screenshots/browse-nhentai.jpg" alt="nhentai tag search and gallery feed" width="360"></a></td>
    <td width="33%"><a href="docs/screenshots/browse-jmcomic.jpg"><img src="docs/screenshots/browse-jmcomic.jpg" alt="JMComic single-volume and webtoon feed" width="360"></a></td>
  </tr>
  <tr>
    <td align="center">E-Hentai / ExHentai: tag and language filters</td>
    <td align="center">nhentai: tag queries and gallery cards</td>
    <td align="center">JMComic: single volumes, manhwa strips, and rankings</td>
  </tr>
</table>

<table>
  <tr>
    <td width="50%"><a href="docs/screenshots/library-shelf.png"><img src="docs/screenshots/library-shelf.png" alt="Unified local and cloud library shelf" width="500"></a></td>
    <td width="50%"><a href="docs/screenshots/browse-serialized.png"><img src="docs/screenshots/browse-serialized.png" alt="Serialized manga categories and rankings" width="500"></a></td>
  </tr>
  <tr>
    <td align="center">Library: local and cloud shelf with +N update badges</td>
    <td align="center">Serialized: categories, filters, and rankings</td>
  </tr>
  <tr>
    <td width="50%"><a href="docs/screenshots/detail-chapters.png"><img src="docs/screenshots/detail-chapters.png" alt="Series detail, tags, and chapter actions" width="500"></a></td>
    <td width="50%"><a href="docs/screenshots/cloud-storage-picker.png"><img src="docs/screenshots/cloud-storage-picker.png" alt="1-Key S3 auto-setup and cloud storage picker" width="500"></a></td>
  </tr>
  <tr>
    <td align="center">Detail: clickable tags, chapter download, and one-tap cloud collect</td>
    <td align="center">Cloud setup: paste one API token for R2/Bunny/B2, or sign in to Drive/WebDAV</td>
  </tr>
</table>

## Download and use

### Android APK

1. Download the latest signed APK (`easemic-<version>.apk`, Android 10 / API 29+) from the [direct download link](https://easemic.com/api/app/download) or [GitHub Releases](https://github.com/dirtyfancy/easemic/releases).
2. Open **Browse** to use the built-in catalogs, or enable adult catalogs (**E-Hentai / ExHentai / nhentai / JMComic**) under **More → Browse settings**.
3. To bind cloud storage, go to **More → Cloud storage** and paste a single API token for **Cloudflare R2**, **Bunny.net**, or **Backblaze B2** (Easemic auto-detects the provider and provisions or binds your `easemic-comics` bucket), or connect **Google Drive**, **OneDrive**, or **WebDAV**. Step-by-step free-tier guides: [Cloud storage setup](https://easemic.com/guides/cloud-storage-api-key/).

Easemic checks `easemic.com` once a day for newer releases and verifies `SHA-256` + package signatures before updating in place.

### Build from source

Requires JDK 17+ and the Android SDK (compile SDK 37):

```bash
./gradlew :app:assembleDebug
```

## FAQ

- **Where do I enable E-Hentai, ExHentai, nhentai, and JMComic?** Fresh installs show standard catalogs by default. Go to **More → Browse settings** to toggle on the adult catalogs; ExHentai unlocks automatically after signing in or pasting cookies.
- **Which cloud storage is fastest or cheapest?** **Cloudflare R2** gives you 10 GB/month free with zero egress fees; **Bunny Storage** has the lowest global page-turn latency (123 ms median first-page open); existing **Google Drive**, **OneDrive**, or **WebDAV** accounts work out of the box with mandatory client-side encryption. Full numbers: [Cloud reading speed benchmarks](https://easemic.com/guides/cloud-reading-speed/).
- **Can Google Drive or OneDrive scan my comics?** No. On Google Drive, OneDrive, and WebDAV, Easemic encrypts every cover, page, and index on your phone with your password before uploading. On S3-compatible buckets (R2, Bunny, B2), you can choose encrypted or plaintext storage.
- **Can I migrate my `.ehviewer` folder or CBZ files?** Yes—tap **Import folder** on the Library tab and pick your `.ehviewer` directory, image folders, or `CBZ`/`ZIP` archives.

## Reporting issues

Open an issue on [GitHub Issues](https://github.com/dirtyfancy/easemic/issues) with your app version, steps to reproduce, and screenshots (please redact personal API tokens or private server URLs).

## Community

Built from scratch in Kotlin and Jetpack Compose. Not affiliated with any upstream catalog or storage provider.
Easemic is shared with and recognizes the [LINUX DO](https://linux.do) community.

## License

[Apache-2.0](LICENSE) · Copyright (C) 2026 Easemic.
