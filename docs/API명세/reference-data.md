# 참조 데이터 API

## 1. GET /api/depts

### API 개요
사용 중인 부서 목록을 조회한다. `sortSeq` 오름차순(값이 없으면 뒤로), 부서코드 순으로 정렬한다. 부서는 로그인 시 IdP의 `groups` 값으로 자동 등재되므로 로그인 이력이 없으면 빈 배열일 수 있다.

로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |

### 호출 예시
```bash
curl -b cookies.txt http://localhost:8180/api/depts
```

### 응답
`200 OK` (`application/json`). 부서 배열을 반환한다.

```json
[
  {
    "deptCd": "example-group",
    "deptNm": "예시부서",
    "sortSeq": null,
    "useYn": "Y"
  }
]
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `[].deptCd` | String | 불가 | 부서코드 (최대 30자) |
| `[].deptNm` | String | 불가 | 부서명. 자동 등재 시 초기값은 부서코드와 같다 |
| `[].sortSeq` | Integer | 가능 | 정렬 순서. 자동 등재된 부서는 `null` |
| `[].useYn` | String | 불가 | 사용 여부. 항상 `Y` |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 세션 없음 또는 만료 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 오류 |

## 2. GET /api/regions

### API 개요
시군구별 읍면동 목록을 트리 형태로 조회한다. 읍면동코드 오름차순으로 정렬하므로 제주시, 서귀포시 순으로 반환한다.

로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |

### 호출 예시
```bash
curl -b cookies.txt http://localhost:8180/api/regions
```

### 응답
`200 OK` (`application/json`). 시군구 배열을 반환한다.

```json
[
  {
    "sggNm": "제주시",
    "emds": [
      {
        "emdCd": "50110560",
        "emdNm": "연동"
      }
    ]
  }
]
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `[].sggNm` | String | 불가 | 시군구명 |
| `[].emds` | Object[] | 불가 | 하위 읍면동 목록 |
| `[].emds[].emdCd` | String | 불가 | 읍면동코드 (8자리) |
| `[].emds[].emdNm` | String | 불가 | 읍면동명 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 세션 없음 또는 만료 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 오류 |

## 3. GET /api/admin-districts

### API 개요
행정동 경계를 GeoJSON FeatureCollection 형식으로 조회한다. 경계 데이터가 있는 행정동만 읍면동코드 오름차순으로 반환하며, 좌표계는 WGS84(EPSG:4326)다.

로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| query | `sggCd` | String | 선택 | 없음 | 시군구코드 5자리. `50110` 제주시, `50130` 서귀포시. 생략하면 전체 |
| query | `emdCd` | String | 선택 | 없음 | 읍면동코드 8자리. `sggCd` 와 함께 지정하면 두 조건을 모두 만족하는 행정동만 반환한다 |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |

### 호출 예시
```bash
curl -b cookies.txt "http://localhost:8180/api/admin-districts?sggCd=50110"
```

### 응답
`200 OK` (`application/json`). 조건에 맞는 행정동이 없으면 `features` 는 빈 배열이다.

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "geometryRaw": "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[126.5,33.4],[126.6,33.4],[126.6,33.5],[126.5,33.4]]]]}",
      "geometry": {
        "type": "MultiPolygon",
        "coordinates": [[[[126.5, 33.4], [126.6, 33.4], [126.6, 33.5], [126.5, 33.4]]]]
      },
      "propertiesRaw": "{\"emdCd\" : \"50110560\", \"emdNm\" : \"연동\", \"sggCd\" : \"50110\"}",
      "properties": {
        "emdCd": "50110560",
        "emdNm": "연동",
        "sggCd": "50110"
      }
    }
  ]
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `type` | String | 불가 | 항상 `FeatureCollection` |
| `features` | Object[] | 불가 | 행정동별 Feature 목록 |
| `features[].type` | String | 불가 | 항상 `Feature` |
| `features[].geometryRaw` | String | 불가 | 경계 GeoJSON의 JSON 문자열 |
| `features[].geometry` | Object | 가능 | 경계 GeoJSON 객체. 파싱에 실패하면 `null` |
| `features[].geometry.type` | String | 불가 | 도형 유형. 예: `MultiPolygon` |
| `features[].geometry.coordinates` | Array | 불가 | `[경도, 위도]` 좌표 배열 |
| `features[].propertiesRaw` | String | 불가 | 속성의 JSON 문자열 |
| `features[].properties` | Object | 가능 | 행정동 속성. 파싱에 실패하면 `null` |
| `features[].properties.emdCd` | String | 불가 | 읍면동코드 (8자리) |
| `features[].properties.emdNm` | String | 불가 | 읍면동명 |
| `features[].properties.sggCd` | String | 불가 | 시군구코드 (5자리) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 세션 없음 또는 만료 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 오류 |

## 4. GET /api/parking-lots

### API 개요
지도 마커에 표시할 공영주차장 목록을 조회한다. 좌표가 있고 소멸 처리되지 않은 주차장만 주차장명, 주차장 ID 순으로 반환한다.

로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| query | `sggCd` | String | 선택 | 없음 | 시군구코드 5자리. `50110` 제주시, `50130` 서귀포시. 생략하면 전체 |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |

### 호출 예시
```bash
curl -b cookies.txt "http://localhost:8180/api/parking-lots?sggCd=50110"
```

### 응답
`200 OK` (`application/json`). 주차장 배열을 반환하며, 조건에 맞는 주차장이 없으면 빈 배열을 반환한다.

```json
[
  {
    "pkltId": 1,
    "pkltNm": "예시 공영주차장",
    "lat": 33.4996,
    "lot": 126.5312,
    "pkltAddr": "제주특별자치도 제주시 예시로 1",
    "emdNm": "연동",
    "pkltCpcty": 42,
    "prkCrgYn": "Y",
    "pkltTypeCd": "ONSTR",
    "pkltTypeNm": "노상주차장"
  }
]
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `[].pkltId` | Long | 불가 | 주차장 ID |
| `[].pkltNm` | String | 불가 | 주차장명 |
| `[].lat` | Number | 불가 | 위도 |
| `[].lot` | Number | 불가 | 경도 |
| `[].pkltAddr` | String | 가능 | 주차장 주소 |
| `[].emdNm` | String | 가능 | 행정동명 |
| `[].pkltCpcty` | Integer | 가능 | 주차 면수 |
| `[].prkCrgYn` | String | 가능 | 주차 요금 여부. `Y` 유료, `N` 무료 |
| `[].pkltTypeCd` | String | 가능 | 주차장구분코드. 공통코드 `PKLT_TYPE_CD` 의 코드값 |
| `[].pkltTypeNm` | String | 가능 | 주차장구분명. 공통코드 `PKLT_TYPE_CD` 의 코드명이며 코드가 없으면 `null` |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 세션 없음 또는 만료 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 오류 |
