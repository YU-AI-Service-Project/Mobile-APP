# WattUp Android

전기차 운전자가 주변 충전소의 현재 가용 대수와 **30분 후 충전기 한 대 이상을 이용할 수 있는 확률**을 비교하는 Kotlin·XML 앱이다. 비회원으로 지도, 장소 검색, 목록, 상세, Top-3 추천, 기기 내 즐겨찾기를 사용할 수 있다. 소셜 로그인은 준비 중이다.

## Android Studio에서 실행하기

1. Android Studio에서 이 README가 있는 `android` 폴더를 연다.
2. Gradle JDK는 JDK 17을 사용한다. SDK Manager에서 Android SDK Platform 35, Build-Tools 35.0.0, Platform-Tools를 설치하고 Gradle 동기화를 마친다.
3. 실행 대상은 `app`, 빌드 변형은 `debug`로 선택한다. 에뮬레이터 또는 USB 디버깅을 켠 Android 기기(API 23 이상)를 선택하고 ▶ Run을 누른다.
4. **로그인 없이 둘러보기**를 누른다. 위치 권한을 거부해도 장소를 검색하거나 지도에서 핀을 선택해 검색할 수 있다.

### 샘플로 화면 확인하기

디버그 빌드는 서버 주소가 비어 있으면 대구 샘플 데이터를 읽는다. 카카오 지도 키도 비어 있으면 개발용 좌표판을 표시한다.

- **장소 검색 → 동성로 검색 지점 → 목록 보기 → 샘플 동성로 충전소** 순서로 연다. 기본 반경 3km, 전체 조건에서 현재 **가용 3/4대**와 **30분 후 76%**를 확인할 수 있다. 이 값은 고정된 샘플 관측값이다.
- 상세의 별표를 누르고 **마이페이지 → 이 기기의 즐겨찾기**에서 저장 결과를 확인한다. 지도에서 **이용 가능만**을 선택하면 같은 검색 지점의 결과가 1곳으로 줄어든다.
- **Top-3 추천**에는 조건에 맞는 후보가 최대 3곳 표시된다. 카드를 누르면 해당 충전소의 상세가 열린다.

## 실제 서버와 지도 연결하기

두 설정은 독립적이다. 환경 변수 또는 이 폴더의 `local.properties`에 필요한 값만 설정하고 앱을 다시 빌드한다. 같은 이름의 환경 변수가 있으면 그 값이 우선한다.

| 설정 | 용도 |
|---|---|
| `WATTUP_API_BASE_URL` | Spring Boot 공개 API의 HTTPS 기본 주소. 끝에 `/api`를 포함한다. 디버그에서도 설정하면 서버를 호출하며, 릴리스 빌드에서 충전소를 조회하려면 이 주소가 필요하다. |
| `KAKAO_NATIVE_APP_KEY` | 카카오 지도 타일 표시용 Native 앱 키. 카카오 개발자 콘솔에서 카카오맵을 활성화하고 Android 패키지 `com.wattup.app`과 실행할 앱의 서명 키 해시를 등록한다. |

Android Studio가 만든 `local.properties`의 `sdk.dir`은 유지한다. 실제 키와 로컬 설정은 Git에서 제외한다. [카카오 지도 Android SDK 시작 안내](https://apis.map.kakao.com/android_v2/docs/getting-started/quickstart/)

## 로컬 백엔드와 예측 시연

실행 중인 팀 Spring Boot 서버와 데이터가 있는 MySQL이 필요하다. Android 에뮬레이터에서 PC의 서버를 사용할 때 이 폴더의 `local.properties`에 아래 두 값을 설정하고 디버그 앱을 다시 빌드한다. 서버 포트는 실제 포트에 맞춘다.

```properties
WATTUP_API_BASE_URL=http://10.0.2.2:8080/api
WATTUP_DEMO_FORECAST=true
```

이 모드의 상세·즐겨찾기·추천은 지원 프로필의 신선한 관측에 한해 고정된 **시연값 75%**를 관측 30분 뒤 값으로 표시한다. 주소·현재 대수·장소 검색·추천 순위와 근거는 백엔드 응답을 사용한다. 추천은 서버가 지원하는 `ARRIVAL_ETA`로 받아오며 시연값은 순위 계산에 사용하지 않는다. 수집된 데이터가 없으면 검색 결과도 비어 있다. 장소명 검색에는 서버의 카카오 키가 필요하다. 릴리스 빌드에서는 시연 옵션이 꺼지고 HTTPS 주소가 필요하다.

통합 테스트는 격리된 빈 개발 DB에 백엔드 마이그레이션을 적용하고 `app/src/test/resources/backend-preview.sql`을 한 번 실행한 뒤 진행한다. 검증용 관측이 10분을 넘기기 전에 Windows에서 다음 명령을 실행한다. 주소는 **테스트를 실행하는 PC** 기준이다.

```powershell
$env:WATTUP_TEST_FIXTURE_URL = "http://127.0.0.1:8080/api"
.\gradlew.bat :app:testDebugUnitTest
```

## 화면과 API의 데이터 기준

- 지도와 목록은 같은 검색 결과를 사용한다. 선택 반경은 500m, 1km, 3km, 5km, 10km다. 지원 충전 방식은 `supportedSpeeds` 또는 `supportedSpeedText`를 읽고, 필요하면 충전기 목록으로 확인한다.
- 급속 조건은 출력 50kW 이상이다. 급속 대수 조회가 실패해도 이미 받은 주소·운영·충전기 정보는 표시한다. 음수나 전체 대수를 넘는 가용 대수 등 모순된 집계는 ‘정보 확인 필요’로 표시한다.
- 예측 확률은 서버의 0~1 값을 화면에서 백분율로 변환한다. 실제 0은 ‘0%’, 누락은 ‘예측 정보 부족’이다. 출력 조건과 모델 프로필이 다르면 다른 조건의 확률을 가져오지 않는다.
- 추천 현재 대수는 원천 관측 시각인 `current.asOf`를 기준으로 10분이 지나면 만료된다. 관측 시각이 없으면 ‘정보 확인 필요’로 표시한다. 추천 생성 시각으로 유효 기간을 연장하지 않는다.
- ‘이용 가능만’ 추천은 서버가 순위 계산 전 필터를 적용하고 `appliedFilters.availableOnly`로 알려주는 방식이다. 확인되지 않은 응답에서는 반환된 후보 중 이용 가능한 곳을 표시하고 제한 사항을 안내한다.

## 빌드와 테스트

Windows PowerShell에서 이 폴더를 기준으로 실행한다.

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

단위 테스트는 모의 HTTP 응답과 디버그 샘플을 사용한다. 테스트 결과는 `app/build/reports/tests/testDebugUnitTest/index.html`, 디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에서 확인한다. 연결된 기기에 설치할 때는 `.\gradlew.bat :app:installDebug`를 실행한다. 한글 경로에서 테스트 클래스 로딩 오류가 발생하면 영문 경로나 임시 드라이브 문자에서 다시 실행한다.
