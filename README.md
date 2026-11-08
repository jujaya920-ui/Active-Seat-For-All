# Active-Seat-For-All

동승자의 멀미를 줄이는 Pleos Connect 차량용 앱입니다. 차량의 움직임을 미리 예측해 시트와 워치로 알려 주어, 동승자가 운전자처럼 움직임에 대비할 수 있게 합니다.

공모전 제출용 프로토타입입니다. 실제 하드웨어 대신 앱 화면에 시트와 워치의 동작을 보여 주고, 주행은 플레오스 에뮬레이터에서 재현합니다.

## 주요 기능

- **움직임 예측:** 차량 신호와 내비 정보로 곧 일어날 회전과 가감속을 예측합니다.
- **시트 힌트:** 예측 결과를 시트의 진동과 기울기, 워치 신호로 바꿔 홀로그램 화면에 표시합니다.
- **데모 내비:** 지도, 즐겨찾기, 경로 미리보기, 턴바이턴 안내를 제공합니다. 즐겨찾기로 주행 시나리오를 고릅니다.
- **주행 시나리오:** 실제 도로 경로로 만든 시나리오를 Virtual Console에 재생합니다.

## 구조

| 폴더 | 내용 |
| --- | --- |
| `app/` | Android 앱 (Kotlin, Jetpack Compose) |
| `tools/` | 시나리오, 지도 생성 등 PC용 도구 |

## 시작하기

JDK 17, Android SDK, Node.js 24, Pleos Connect 에뮬레이터가 필요합니다.

앱을 빌드합니다.

```bash
./gradlew assembleDebug
```

테스트를 실행합니다.

```bash
./gradlew testDebugUnitTest
```

```bash
node --test tools/test/
```

도구 사용법은 [tools/README.md](tools/README.md)를 참고하세요.

## 데이터 출처

- 지도: © OpenStreetMap contributors · OpenFreeMap © OpenMapTiles
- 경로: OSRM 데모 서버 응답, © OpenStreetMap contributors (ODbL)
