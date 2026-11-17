@echo off
setlocal
cd /d "%~dp0"

set "JAR=target\jcode-0.1.0-SNAPSHOT.jar"

where java >nul 2>&1
if errorlevel 1 (
  echo Java が見つかりません。JDK 21 を PATH に追加してください。
  exit /b 1
)

if not exist "%JAR%" (
  where mvn >nul 2>&1
  if errorlevel 1 (
    echo %JAR% がありません。
    echo 先に "mvn -DskipTests package" を実行するか、Maven を PATH に追加してください。
    exit /b 1
  )
  echo ビルド中…
  call mvn -q -DskipTests package
  if errorlevel 1 (
    echo ビルドに失敗しました。
    exit /b 1
  )
)

java -jar "%JAR%" %*
exit /b %ERRORLEVEL%
