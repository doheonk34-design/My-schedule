# 교대노트

교대근무 스케줄·알람·메모 앱입니다. 한 저장소에서 두 가지로 배포됩니다.

- **아이폰 등 웹앱(PWA)**: GitHub Pages가 저장소 최상위 파일(`index.html` 등)을 그대로 서비스합니다. 홈 화면에 추가해서 씁니다.
- **안드로이드 앱(APK)**: GitHub Actions가 같은 `index.html`을 Capacitor로 감싸 APK를 빌드합니다. 홈 화면 위젯 3종과 네이티브 알람이 들어 있습니다.

## 폴더 구조

```
index.html, manifest.json, sw.js, icon-*.png   ← 앱 화면 원본 (PWA·APK 공통, 이것만 수정)
android/                                        ← 안드로이드 네이티브 프로젝트 (위젯·알람·플러그인 Java 코드)
worker/calendar-worker.js                       ← 캘린더 구독 주소를 만드는 Cloudflare 워커 코드
firestore.rules                                 ← Firestore 보안 규칙 (Firebase 콘솔에 게시해서 적용)
IPHONE_ALARM.md                                 ← 아이폰 시계 알람 연동(단축어) 설정 안내
.github/workflows/build-apk.yml                 ← APK 자동 빌드
```

`www/`와 `android/app/src/main/assets/public/`은 빌드할 때 자동으로 만들어지는 복사본이라 저장소에 올리지 않습니다.

## 앱 화면을 수정하려면

1. 저장소 최상위 `index.html`을 수정하고 `main`에 커밋·푸시합니다.
2. 웹앱(PWA)은 GitHub Pages가 자동으로 갱신합니다. 앱을 열면 "새 버전이 있어요" 안내가 뜨고, 새로고침하면 반영됩니다.
3. APK는 Actions 탭의 **Build Android APK**가 자동으로 빌드합니다. 끝나면 실행 결과 페이지 맨 아래 **Artifacts**에서 `shift-note-debug-apk`를 받습니다.

## APK 서명 키 (중요)

안드로이드는 **같은 서명 키로 만든 APK만 업데이트로 설치**해 줍니다. 서명 키는 저장소에 두지 않고 GitHub Secrets에만 보관합니다.

저장소 Settings → Secrets and variables → Actions에 아래 4개가 있어야 빌드됩니다(없으면 빌드가 일부러 중단됩니다).

| 이름 | 내용 |
|---|---|
| `SHIFTNOTE_KEYSTORE_BASE64` | 키 파일(`shiftnote.keystore`)을 base64로 바꾼 값 |
| `SHIFTNOTE_KEYSTORE_PASSWORD` | 키 저장소 비밀번호 |
| `SHIFTNOTE_KEY_ALIAS` | 키 별칭 (`shiftnote`) |
| `SHIFTNOTE_KEY_PASSWORD` | 키 비밀번호 |

키 원본은 저장소 밖에 따로 백업해 두세요. **키를 잃어버리면 다음 업데이트 때 앱을 지우고 새로 설치해야 합니다.**

## 알람

- **안드로이드**: 네이티브가 앞으로 14일치 알람을 등록하고 매일 스스로 다시 계산합니다. 재부팅·앱 업데이트 후에도 다시 등록되므로 앱을 열지 않아도 계속 울립니다. 처음 실행할 때 알림·정확한 알람 권한을 허용해야 합니다.
- **아이폰**: 웹앱은 알람을 직접 울릴 수 없어서, 단축어 자동화로 시계 앱 알람을 켜고 끕니다. [IPHONE_ALARM.md](IPHONE_ALARM.md)를 참고하세요.

## 홈 화면 위젯 (안드로이드)

오늘 근무, 한 달 달력, 메모(오늘/다가오는 일정) 위젯이 있습니다. 앱을 안 열어도 날짜가 바뀌면 30분 안에 갱신되고, 기기 간 동기화를 쓰는 경우 다른 기기에서 바꾼 근무표·메모도 30분마다 가져옵니다.

## 서버 (Firebase / Cloudflare)

- **Firestore**: 기기 간 동기화(`shiftNoteSync`), 공유 링크·캘린더 구독·아이폰 알람 계획(`shiftNoteShare`)을 저장합니다. 앱에 로그인 기능이 없어서 **8자리 코드가 곧 열쇠**입니다. 코드를 남에게 보여주지 마세요.
- **보안 규칙**: `firestore.rules`를 수정했다면 Firebase 콘솔 → Firestore → 규칙에 붙여넣고 게시해야 적용됩니다.
- **캘린더 구독 워커**: `worker/calendar-worker.js`를 Cloudflare 대시보드의 워커 코드 편집 화면에 붙여넣고 배포합니다. 끄거나 없어진 구독에는 빈 캘린더를 돌려줘서, 캘린더 앱이 예전 일정을 지우게 합니다.
