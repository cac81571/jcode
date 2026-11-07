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
