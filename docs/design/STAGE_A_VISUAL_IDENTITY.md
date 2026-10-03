# Stage A · Visual Identity

Status: implementation branch `feature/world-between-control-v1-20261003`.

## Frozen visual rules

- Launcher / PWA primary artwork uses the user-approved **Between Worlds** day/night composition.
- Preserve the complete composition. Only shallow outer-edge cleanup and platform safe fitting are allowed.
- The planet image is **JLZ's small conversation identity avatar only**.
- Keep the existing large male portraits on Home and Welcome.
- The entertainment/focus gate will be redesigned later in Stage G using the approved incoming-call two-state visual reference. Stage A does **not** implement that new gate layout.

## Asset mapping

| Surface | Asset |
| --- | --- |
| Native launcher foreground | `drawable-nodpi/ic_launcher_visual.webp` |
| Web/PWA 192 icon / touch icon | `public/icon-192.webp` |
| Web/PWA 512 icon / favicon | `public/icon-512.webp` |
| Android notification Person avatar | `drawable-nodpi/jlz_chat_avatar.webp` |
| Current native FocusGate identity avatar | `drawable-nodpi/jlz_chat_avatar.webp` |
| Native full-screen callback identity avatar | `drawable-nodpi/jlz_chat_avatar.webp` |
| Native assistant-side timeline avatar | `drawable-nodpi/jlz_chat_avatar.webp` |
| Echo header / companion message avatar | `public/jlz-chat-avatar.webp` |
| Home large portrait | existing `jlz-home-portrait.webp` (unchanged) |
| Welcome large portrait | existing `jlz-welcome-portrait.webp` (unchanged) |

## Notification constraints

The existing direct-reply and reply-context chain must remain intact:
`Notification → inline reply → PendingReplyStore → Runtime → pending fast read → ACK`.

Notifications use Android `Person + MessagingStyle` with the name **纪临洲** and the new planet avatar.

## Stage A acceptance

1. No visible black `JLZ` initials badge remains in FocusGate or the full-screen callback; assistant-side native timeline identity also uses the avatar.
2. Echo uses the planet avatar for JLZ identity while Home/Welcome large portraits remain unchanged.
3. Native launcher uses the approved Between Worlds artwork.
4. Android notification direct reply still compiles and passes regression tests.
5. Web TypeScript + Vite production build passes.
6. No official latest APK is published from the Stage A PR.
