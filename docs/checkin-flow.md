# MUJI passport チェックインフロー調査メモ

調査日: 2026-10-28 / 端末: Galaxy S26 (SM-S942Q, Android 16, SDK 36) / アプリ: net.muji.passport.android v6.8.0 (versionCode 186)

## アプリの前提
- 位置情報権限は FINE/COARSE のみ。`ACCESS_BACKGROUND_LOCATION` なし → アプリが前面にいないと店舗判定されない。
- 画面は FLAG_SECURE（スクショは黒）。ただし UI ツリー（Accessibility / uiautomator）は読める。
- 起動: `MainActivity`。ディープリンク `mujipassport://`（SchemeActivity）、App Link `https://muji.go.link`。

## 画面遷移
1. ホームタブ（`id/from_muji_fragment`）
   - 店舗近くにいると `id/checkin_snackbar_view` が出る
   - 複数店舗時: テキスト「複数店舗が存在します」+「チェックイン」（クリック可能なのは「チェックイン」TextView の親 View、resource-id なし）
2. 帯の「チェックイン」をタップ → 「店舗検索」画面（`id/shop_search_fragment`）
   - 見出し `id/check_in_shop_label`「現在地からチェックインできる店舗」
   - リスト `id/check_in_shop_recycler` の各行 `id/check_in_shop`
     - `id/shop_name` 店舗名
     - `id/check_in` ボタン: 未チェックイン=「チェックイン」(enabled=true) / 済み=「チェックイン済み」(enabled=false)
3. `id/check_in` タップ → ダイアログ等なしで即「チェックイン済み」に変わる
   - 1店舗ずつ独立（他店舗のボタンは押せるまま）
   - 未チェックイン店舗が残っている間はホームの帯も残る

※ 単一店舗時の帯の文言・遷移は未確認（店舗検索画面を経由せず直接チェックインされる可能性あり）。

## 自動化での操作手順（案）
1. MUJIアプリを起動 → ホームタブを選択
2. `checkin_snackbar_view` 内の「チェックイン」テキストの clickable な親を ACTION_CLICK
3. 店舗検索画面に遷移したら、`id/check_in` で text=「チェックイン」かつ enabled のものを全て（またはマイル目的なら1件）ACTION_CLICK
4. 「チェックイン済み」を確認 → GLOBAL_ACTION_BACK / HOME で元に戻る
