@echo off
chcp 65001 >nul
setlocal
title GOFU-AI 一键打包

echo ==========================================
echo    GOFU-AI 便携版 一键打包
echo ==========================================
echo.
set /p VER=请输入版本号(如 1.7.0)，回车开始打包:
if "%VER%"=="" (
  echo 没输版本号，已取消。
  pause
  exit /b 1
)

echo.
echo 开始打包 v%VER% … 全程约 3-5 分钟，请勿关闭本窗口。
echo.

set BASH="C:\Program Files\Git\bin\bash.exe"
if not exist %BASH% (
  echo 找不到 Git Bash: %BASH%
  echo 请确认已安装 Git for Windows。
  pause
  exit /b 1
)

%BASH% -lc "cd /d/code/gofu-ai/packaging && bash 一键打包.sh %VER%"
set RC=%ERRORLEVEL%

echo.
if %RC%==0 (
  echo ===== 打包成功 v%VER% =====
) else (
  echo ===== 打包失败 (错误码 %RC%)，请把上面的红字/报错发回排查 =====
)
pause
endlocal
