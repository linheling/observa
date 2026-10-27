@echo off
rem 开发用：启动客户端并自动加入本机开发服（--quickPlayMultiplayer 需要带空格参数，
rem 直接写在一行 bash -> cmd 里会被引号解析破坏，所以放进 bat）。
cd /d D:\C\MC\test
gradlew.bat runClient --console=plain "--args=--quickPlayMultiplayer 127.0.0.1:25565"
