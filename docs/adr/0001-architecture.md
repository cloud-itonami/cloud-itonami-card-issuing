# ADR-0001: cloud-itonami-card-issuing — Card Issuing Advisor を封じ込めた知能ノードとするissuer側カード発行プログラム管理アクター設計

**Status**: accepted
**Date**: 2026-07-25
**Deciders**: Jun Kawasaki (+ Claude、オーナー承認のうえ実行)

## Context

`com-junkawasaki/root` superprojectでの複数ラウンドの決済業界調査
（`90-docs/adr/2607246000-adult-content-payment-processor-banking-jurisdiction-research.edn`）
を通じて、cloud-itonami fleetの既存カード関連actor `cloud-itonami-isic-6619`
（Card Advisor ⊣ Card Settlement Governor）が**カード取引処理・清算 ──
acquirer/加盟店(merchant)側**のみをカバーし、**カードを実際に発行する
issuer側**（BIN/レンジのスキーム・スポンサーシップ、カード会員口座の
プロビジョニング、real-timeオーソリ判断、カードのライフサイクル管理）を
担うactorがfleetに存在しないことが判明した。並行して、e-money発行体
（EMI）を発行するactor `cloud-itonami-emi` も同時に新設されている。

## Decision

新規actor `cloud-itonami-card-issuing` を、`cloud-itonami-isic-6910`
（会社設立代行）・`cloud-itonami-isic-6419`（銀行業務）と同型の
「封じ込め + 独立governor + 不変台帳」actorパターンで実装する。

### 1. Card Issuing Advisorは最下層の1ノードに封じ込め、直接actuationさせない

`cardissuing.cardissuingadvisor`はintake正規化・BIN/スキーム要件
チェックリスト・KYC/制裁スクリーニング・BIN/レンジ・スポンサーシップ
締結案・カード発行案・カードライフサイクル遷移案・real-timeオーソリ
判断案・ディスピュート起票案の8種類のproposalのみを返す。

### 2. OperationActor = langgraph-clj StateGraph、1 run = 1 カード発行操作

`cardissuing.operation`は`cloud-itonami-isic-6910`の`formation.operation`
と同型の advise → govern → decide → commit | hold | request-approval
のStateGraphで、`interrupt-before #{:request-approval}`によるhuman-in-
the-loopを持つ。

### 3. Card Issuing Governorは Card Issuing Advisor と別系統

`cardissuing.governor`は16のHARD check（人間承認でも上書き不可）+ 2の
SOFT check（confidence floor / actuation gate）を持つ。HARD checkの
優先順位・設計意図は`cardissuing.governor`自身のnamespace docstringに
詳細記載。特筆すべき設計判断:

- **effect-mismatch check を最優先に置く**（`cloud-itonami-isic-6910`の
  `op->effect`パターンをそのまま踏襲）: 未検証のadvisor（将来real LLM化
  したとき幻覚しうる）が、無害に見える`:bin/assess`リクエストに対して
  `:effect :card/mark-issued`を返しても、governorが即座にHARD違反として
  弾く。個々のop固有チェックは全て**リクエストの`:op`**を基準に動作し、
  advisorの自己申告する`:effect`を信用しない。
- **Luhn (ISO/IEC 7812-1 mod-10) 独立再計算チェック**
  （`card-reference-checksum-invalid-violations`）は、
  `cloud-itonami-isic-6419`のIBAN ISO 7064 MOD 97-10チェックと
  `cloud-itonami-isic-6910`のLEI検算と同じ「既存のground-truthフィールド
  を、提案の自己申告を一切信用せず独立に再計算する」ディシプリンの
  第三の実例。`cardissuing.registry/assign-card-reference`が構成する
  合成カード参照識別子は、実際のネットワーク発行PANでは**ない**
  （下記5節参照）が、この検算ディシプリンを別のアルゴリズムに適用する
  demonstationとして採用した。
- **velocity limit / MCC制限 / 残高・与信枠チェック**は、
  `:authorization/decide`のproposalが`:decision :approve`を宣言している
  ときのみ発火する ── advisorの`decide-authorization`関数自体は正直な
  演算で承認/拒否を判断するため、通常のmock-advisor経由では絶対に
  違反を起こせない。これらのHARD checkが実際に機能することを証明する
  テストは、`cardissuing.governor/check`を直接、手で作った「嘘をつく」
  proposalで呼び出す方式を採る（`test/cardissuing/governor_contract_test.clj`
  の"ground-truth governor recompute"節、`test/cardissuing/
  llm_advisor_test.clj`と同型のテクニック）。

### 4. 実アクチュエーションは構造的に常に人間専用（2層で独立に強制）

`:bin/sponsor`・`:card/issue`・`:card/lifecycle`・`:authorization/decide`・
`:dispute/initiate`の5opは、governorの`:actuation` high-stakes gateと
`cardissuing.phase`のphase table（どのphaseの`:auto`集合にも含まれない）
の両方で、独立に常にescalateする。`:cardholder/intake`のみが唯一の
自動commit対象（phase 3、governor-clean時のみ）で、`post-issue-intake-
block`（一度カードが`:intake`を超えて進んだレコードへのintake経由の
書き換えを遮断）と`intake-fabrication`（`:card-reference`/`:card-issued?`
の捏造、および`:status`を`:intake`以外に設定することを禁止）の2つの
ガードで、`cloud-itonami-isic-6910`の`:application/intake`が経験した
「唯一の自動commit opが無検証の抜け穴になる」問題を最初から塞ぐ。

### 5. 生PAN(Primary Account Number)は一切扱わない

`cardissuing.store`のスキーマには生PANフィールドが一切存在しない
（`cloud-itonami-isic-6619`と同じ立場）。`cardissuing.registry/assign-
card-reference`が構成する16桁の識別子は、**このactor自身のドラフト
記録専用の合成(synthetic)識別子**であり、実際のカードスキームが発行する
PANではない。実PANのトークナイゼーション・EMVチップのパーソナライゼー
ションはoperatorの責任範囲かつ本actorのドメインロジック外の外部ベンダー
統合ポイントとして扱う。

### 6. 配置は`cloud-itonami`org直下の独立blueprint repo

`cloud-itonami-isic-6910`と同じ慣例に従い、`cloud-itonami`org直下に
public/AGPL-3.0-or-laterのopen business blueprintとして新設し、
`manifest/west.yml`への登録は行わない（standalone repo）。

## 姉妹actorとの境界（README「Scope」節と対）

- `cloud-itonami-isic-6619`（acquirer/加盟店側のカード取引処理・清算） --
  本actorは複製しない。issuer側の`:dispute/initiate`と6619の
  acquirer側チャージバック処理は、同じカードネットワークの逆方向の
  当事者であり、互いに補完する。
- `cloud-itonami-emi`（e-money発行体） -- 本actorはそのウォレットを
  資金源として参照するのみ。e-money発行ロジックは持たない。
- `cloud-itonami-pi`（PSD2 payment-service executionの広いスコープ） --
  本actorはissuer固有業務に限定。
- `cloud-itonami-isic-6419`（銀行預金口座）・`cloud-itonami-isic-6492`
  （与信枠） -- funding-account候補の実装済みactor。本actorはこれらの
  namespaceを直接requireせず、疎結合（`:funding-account-ref`という
  文字列参照のみで繋がる）。

## R0スコープ（正直な現状）

4法域（JPN/USA/GBR/DEU）のみspec-basisを持つ（`cardissuing.facts/coverage`
で常に正直に報告）。Storeは`MemStore`のみ。63 tests / 223 assertions、
lint clean、`clojure -M:dev:run`でsponsor→issue→activate→authorize→
disputeの一連の流れと、3つのHARD holdケース（二重発行・制裁ヒット・
法域要件捏造）を確認済み。

`cloud-itonami-isic-6619`が既に持つWASM kernel tier（`wasm/*.kotoba`、
`kototama.tender`ホスト）は本actorには実装しない。将来のmaturity昇格
パスとして、`cardissuing.registry`のLuhn検算コアを`.kotoba`へポートする
候補として残す（`banking.kernels.gate`の先例に倣う）。

## Consequences

- (+) cloud-itonami fleetのカード決済カバレッジがacquirer側/issuer側の
  両方に拡張された。
- (+) 実アクチュエーション不変条件は
  `test/cardissuing/phase_test.clj`の`actuation-ops-never-auto-at-any-
  phase`で機械的にリグレッション検出できる。
- (-) spec-basisは4法域のみ。拡張は`cardissuing.facts/catalog`への
  追記（必ず公式ソースを引用、捏造禁止）。
- (-) Datomic/kotoba-server backend未接続、実カードスキーム統合・実
  PCI-DSSインフラ・実EMVチップパーソナライゼーション物流はoperatorの
  責任範囲としてスコープ外。

## 代替案と不採用理由

- **`cloud-itonami-isic-6619`にissuer側opsを追加する案** -- 不採用。
  isic-6619自身のREADMEが明確にacquirer/加盟店側にスコープを限定して
  おり（README「Scope」節、生PAN不所持と並ぶ明示的な境界宣言）、
  issuer側の追加は既存actorのスコープ契約を破る。別actorとして新設する
  方が、既存の governor invariant を壊さず、両者を独立にfork/deployできる。
- **`cloud-itonami-emi`に統合する案** -- 不採用。e-money発行とカード
  発行は概念的に隣接するが別の規制対象（前払式支払手段発行者 vs
  クレジットカード発行者/包括信用購入あっせん業者）であり、`cloud-
  itonami-isic-6491`（financial leasing）と`cloud-itonami-isic-6492`
  （credit granting）が別actorとして分離されている先例に倣う。

## References

- `90-docs/adr/2607249000-cloud-itonami-card-issuing-actor.edn`
  （superproject側ADR、本ADRと対）
- `cloud-itonami-isic-6910`ADR-0001（同型actorパターンの先例）
- `cloud-itonami-isic-6419`（banking, IBAN ISO 7064 MOD 97-10検算の先例）
- `cloud-itonami-isic-6619`README「Scope」節（acquirer/加盟店側の境界を
  定義する対の文書）

## Closing note

このactorはR0スコープで新設された。governor/phase/store/registry/
advisorの各層は実コードとして動作し、テストで検証済みである。今後の
拡張（Datomic backend、実カードスキーム統合、法域拡大、WASM kernel
tierへの昇格）は、既存の governor invariant を壊さずに追加していく。
