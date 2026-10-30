# Syncthing 내장 시험 앱 (프로토타입)

폰 앱에 Syncthing 을 내장할 수 있는지 확인한 일회용 시험 앱입니다(2026-10-31). 앱에 함께 넣은 `libsyncthingnative.so`(Syncthing 실행 파일)를
`ProcessBuilder` 로 직접 실행하고, **앱이 스스로 정한 API 키**(`--gui-apikey`)로 REST 를 호출합니다. 사용자가 키를 복사할 일이 없습니다.

## 확인한 것 (안드로이드 14 에뮬레이터, x86_64)
- 실행 파일은 `nativeLibraryDir` 에서 실행되고(`extractNativeLibs=true` / `useLegacyPackaging=true` 필요) Syncthing 2.1.7 이 뜹니다. 기기 ID 생성·발견 서버·중계·공유기 통과(NAT 감지)까지 동작합니다.
- `http://127.0.0.1` 은 안드로이드가 기본으로 막으므로 `network-security-config` 로 127.0.0.1 만 허용합니다(`res/xml/net.xml`).
- 앱이 정한 키는 통과하고 틀린 키는 403.
- **모든 파일 접근**(`MANAGE_EXTERNAL_STORAGE`)을 허용하면 같은 프로세스가 `/storage/emulated/0/Documents/…` 에 폴더 표식(`.stfolder`)을 만들고 파일을 인식합니다.
- 홈으로 나가기만 하면 계속 살아 있지만, 시스템이 앱을 정리하면(`am kill`) 자식 프로세스도 같이 죽습니다 → 실제 앱에는 **포그라운드 서비스**(알림)가 필요합니다.
- 아직 안 해 본 것: **실제 폰(arm64)**, 포그라운드 서비스, 배터리 절약 예외, 두 기기 사이의 실제 동기화, 앱 용량(실행 파일 약 37MB × ABI).

## 실행 파일 받는 곳 (저장소에는 넣지 않았습니다)
Syncthing-Fork 릴리스의 APK 안 `lib/<abi>/libsyncthingnative.so` 를 꺼내 `app/src/main/jniLibs/<abi>/` 에 둡니다(MPL-2.0).
시험에 쓴 것: `com.github.catfriend1.syncthingfork_release_v2.1.7.0_x86_64.apk`(19,970,414바이트, SHA-256 `464e35b0dfd67a19a7d11c8673a56fac975d469de4c69477206eda96edcd050c`),
GitHub `Catfriend1/syncthing-android` 릴리스 v2.1.7.0 (주소는 `researchxxl/syncthing-android` 로 이전돼 있음). 이 앱은 `abiFilters` 가 x86_64 뿐이니 폰용은 arm64-v8a 를 추가하세요.

## 실행
```
cd prototypes/syncthing-embed   # 영문 경로 사본에서 빌드하세요(한글 경로는 단위 시험을 못 찾습니다)
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set kr.iamtalker.sttest MANAGE_EXTERNAL_STORAGE allow
adb shell am start -n kr.iamtalker.sttest/.MainActivity
adb shell run-as kr.iamtalker.sttest cat files/result.txt
```
