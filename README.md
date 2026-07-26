# 교대노트 — 안드로이드 APK 자동빌드

이 저장소를 GitHub에 올리면, GitHub Actions가 자동으로 APK를 빌드해줍니다.
Android Studio나 SDK를 직접 설치할 필요가 없습니다.

## 폴더 구조

```
index.html, manifest.json, sw.js, icon-*.png   ← GitHub Pages(아이폰 PWA)가 서빙하는 "진짜" 원본
www/                                            ← APK 빌드용 폴더 (빌드할 때마다 위 원본을 자동 복사해감)
android/                                        ← Capacitor가 생성한 안드로이드 네이티브 프로젝트
.github/workflows/build-apk.yml                 ← 자동 빌드 설정
```

**중요**: `index.html`은 **저장소 최상위 것 하나만 관리하면 됩니다.**
`www/index.html`은 빌드할 때마다 자동으로 최신 내용으로 덮어써지므로 따로 손댈 필요 없어요.

## 사용 방법

1. 이 프로젝트 파일 전체(`www`, `android`, `.github` 폴더 포함)를 GitHub 저장소에 업로드합니다.
   - `node_modules`, `android/app/build` 폴더는 올릴 필요 없습니다 (자동 생성됨).
2. 저장소 상단 **Actions** 탭 클릭 → **Build Android APK** 워크플로우가 자동으로 실행되는 걸 확인합니다.
3. 빌드가 끝나면(초록 체크 ✅) 그 실행 결과 페이지 맨 아래 **Artifacts** 섹션에서
   `shift-note-debug-apk`를 다운로드합니다. (zip 파일 안에 `.apk`가 들어있음)
4. 압축을 풀어 `.apk` 파일을 갤럭시로 옮긴 뒤 설치합니다.
   - 처음 설치 시 "출처를 알 수 없는 앱" 허용이 필요할 수 있습니다 (설정 → 보안).

## 앱 화면을 수정한 뒤 업데이트하려면

1. 저장소 최상위 `index.html`만 새 버전으로 교체 (PWA/APK 둘 다 이거 하나로 반영됨)
2. 커밋 & 푸시
3. Actions 탭에서 새 빌드가 자동으로 돌고, 새 APK를 다시 다운로드하면 됩니다.

## 참고

- 지금 만들어지는 건 **디버그 APK**입니다 (테스트/개인 설치용). 구글 플레이스토어에 정식 등록하려면
  서명(release) 빌드와 개발자 계정이 별도로 필요합니다.
- 홈 화면 "진짜 위젯"은 이 방식(웹뷰 감싸기)으로는 만들 수 없고, 별도 네이티브(Kotlin) 코드가 필요합니다.
