#!/data/data/com.termux/files/usr/bin/bash
# Termux(F-Droid 버전) 에서 1회 실행: bash setup_termux.sh
set -e
pkg update -y
pkg install -y python git android-tools termux-api
termux-wake-lock || true
[ -f config.json ] || cp config.example.json config.json
mkdir -p state logs shots
echo "완료. README_20261103.md 의 2~3단계(ADB 연결, 알림 권한)를 진행하세요."
