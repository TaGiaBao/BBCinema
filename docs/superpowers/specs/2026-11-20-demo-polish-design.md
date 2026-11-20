# Demo polish before the defence (2026-11-26)

## Why
The student will show the lecturer every feature, front end to back end. Every screen must look right, and
features that depend on outside services (e-mail, VNPay) must be demonstrable on a laptop.

## Goals
1. Fix the UI defects found in the 2026-11-20 screenshot review.
2. Make sent e-mails visible without a real mailbox (demo mailbox).
3. One command to reset the demo data; a written demo script.

## Non-goals / anti-goals
- No new business features; no security hardening (option B was not chosen).
- Nothing that only works online: the demo must survive a dead Wi-Fi (except map tiles and VNPay).

## Decisions
| Defect | Fix |
|---|---|
| Banner title runs into the play graphic; banner text cannot be translated | Banner artwork without text. Title, subtitle and an "Đặt vé ngay / Book now" button are HTML over the image. `banners` gets `subtitle`, `title_en`, `subtitle_en`, `show_caption` (Flyway V7). Admin form edits them. |
| ★ rating badge covers the age label drawn into the poster | Age label leaves the poster artwork; the card shows it as an HTML badge at the **top left** of the poster, rating at the **bottom right** (user's choice). The BBCINEMA wordmark moves to the top right; the tagline moves up so the rating does not cover it. |
| 405 and other unmapped errors show Spring's whitelabel page with a stack trace | Generic `templates/error.html`; `server.error.include-stacktrace: never` in every profile. |
| "SUCCESS" on the ticket | `PaymentStatus` gets Vietnamese/English labels like the other enums. |
| Customers see "Chưa cấu hình mã VNPay sandbox trong application.yml" | Customer-facing wording; the technical hint is logged at start-up instead. |
| "Tra cứu" button wraps; home tabs wrap on phones | `text-nowrap`; tab strip scrolls horizontally on small screens. |

Demo mailbox (Claude's call): when `app.demo-mailbox=true` (on in profile `h2`, refused by the production
safety check), every e-mail the app sends is also kept in memory (last 50) and listed at `/demo/mailbox`,
linked from the internal sidebar. Real SMTP sending is unchanged.

Reset: `scripts/reset-demo.cmd` stops nothing by itself; it deletes `data/` and starts the app with profile `h2`.
Demo script: `docs/demo-script.md` in Vietnamese.

VNPay: the student registers a sandbox account and puts the keys in `.env`; no code change.
