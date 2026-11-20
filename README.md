# jcode

Java / Maven / Swing / FlatLaf / JGit で動く、VSCode 風の簡易 IDE です。

フォーカスは次の3つです。

- フォルダを開いてコードを編集する
- 検索・置換（ファイル内 / ワークスペース）
- Git（status / stage / unstage / commit / diff）

> EGit は Eclipse 向けプラグインです。スタンドアロンアプリでは EGit と同じエンジンの **JGit** を使います。

## 必要環境

- JDK 21+
- Maven 3.9+

## 起動

```bash
mvn -q compile exec:java
```

フォルダを指定して起動:

```bash
mvn -q compile exec:java -Dexec.args="C:/path/to/project"
```

fat jar:

```bash
mvn -q package
java -jar target/jcode-0.1.0-SNAPSHOT.jar
```

Windows では `run.bat` からも起動できます（jar が無ければ Maven で package してから起動します）。

## ファイル横断検索（ripgrep）

ワークスペース検索は [ripgrep](https://github.com/BurntSushi/ripgrep) があれば外部プロセスとして使い、JSON を読みながら結果を逐次表示します。見つからないときは従来の Java 実装に戻り、検索結果の件数のあとに `Java・ripgrep なし` と出ます。

探す順番は次のとおりです。

1. jar と同じフォルダの `rg.exe`（Windows）または `rg`（Linux / macOS）
2. 起動時の作業フォルダ（`run.bat` と同じ場所に置いた場合）
3. メニュー **ファイル → ripgrep のパスを設定…**
4. `PATH`

### rg.exe の置き方（Windows）

1. [ripgrep の Releases](https://github.com/BurntSushi/ripgrep/releases) から `x86_64-pc-windows-msvc` の zip を取得し、中の `rg.exe` を取り出します。
2. 配布するときは `jcode-….jar` と同じフォルダに `rg.exe` を置きます。
3. `run.bat` で開発ツリーから起動するときは、`run.bat` と同じフォルダ（プロジェクト直下）に置くか、`PATH` が通っている場所に置きます。`target\` は `mvn clean` で消えるので、そこだけに置かないでください。

Linux / macOS ではパッケージの `rg` が `PATH` にあればそのまま使われます。jar の隣に実行権限付きの `rg` を置いても見つかります。

大文字小文字・正規表現・単語単位・Include / Exclude は今までどおりのチェックです。正規表現は Java の `Pattern` ではなく ripgrep（Rust regex）で評価します。単語単位は ripgrep の Unicode 単語境界です。ASCII だけの文字列検索は 1 回、日本語など非 ASCII や正規表現は UTF-8 と Windows-31J（Shift_JIS / MS932）の 2 回検索して、文字化けしない行を採用します。「開いているファイルのみ」は未保存のバッファを見るため、従来の Java 検索のままです。

## 主なショートカット

| 操作 | キー |
|------|------|
| Open Folder | Ctrl+Shift+O |
| Open File | Ctrl+O |
| Save | Ctrl+S |
| Find | Ctrl+F |
| Replace | Ctrl+H |
| Search in Files | Ctrl+Shift+F |
| Source Control | Ctrl+Shift+G |
| Explorer | Ctrl+Shift+E |

## 画面構成

```
┌──────────┬─────────────────────────────┐
│ Explorer │  Editor tabs                │
│ Search   │  Find/Replace bar           │
│ Git      │  RSyntaxTextArea            │
├──────────┴─────────────────────────────┤
│ Status bar                             │
└────────────────────────────────────────┘
```

## モジュール

| パッケージ | 役割 |
|------------|------|
| `com.jcode.ui.editor` | タブ付きエディタ |
| `com.jcode.ui.search` | ファイル内検索・ワークスペース検索 |
| `com.jcode.ui.git` | Source Control ビュー |
| `com.jcode.service` | SearchService / GitService |

## 次に足しやすいもの

- ターミナル（ProcessBuilder + JPanel）
- 複数選択 stage / discard
- push / pull / fetch（認証 UI）
- 設定画面（テーマ・フォント）
