# DDD-Mission

회원(Member)과 게시글(Post)을 서로 다른 bounded context로 분리하고, 회원 데이터는 게시글 컨텍스트에 복제해 사용하는 예제입니다.

## 1. 실행 방법

### 요구 환경

- JDK 25
- Gradle Wrapper 9.7.1
- H2 Database 2.4.240

Gradle toolchain과 실제 실행 환경은 Java 25를 사용합니다.

### DB 설정

기본 profile은 dev입니다.

~~~yaml
# src/main/resources/application-dev.yaml
spring:
  datasource:
    url: jdbc:h2:./db_dev;MODE=MySQL
    username: sa
    password:
~~~

애플리케이션 실행 시 프로젝트 루트에 db_dev.mv.db 파일이 생성됩니다. H2 콘솔은 다음 주소에서 사용할 수 있습니다.

~~~
http://localhost:8080/h2-console
JDBC URL: jdbc:h2:./db_dev;MODE=MySQL
User Name: sa
Password: (없음)
~~~

### 실행

~~~bash
./gradlew bootRun
~~~

애플리케이션은 8080 포트로 실행됩니다.

### 테스트

개발 서버가 db_dev.mv.db를 사용 중이면 파일 잠금이 발생할 수 있으므로, 테스트는 인메모리 H2를 사용하는 방법을 권장합니다.

~~~bash
SPRING_DATASOURCE_URL='jdbc:h2:mem:dddmissiontest;MODE=MySQL;DB_CLOSE_DELAY=-1' ./gradlew test
~~~

## 2. 구조 설명

현재 Gradle 프로젝트는 하나이며, 애플리케이션 내부를 bounded context 단위의 패키지로 나눴습니다.

~~~
com.back
├── boundedContext
│   ├── member
│   │   ├── app       # MemberFacade, 회원 가입 유스케이스
│   │   ├── domain    # Member, MemberPolicy
│   │   ├── in        # HTTP Controller, 초기 데이터, 이벤트 Listener
│   │   └── out       # MemberRepository
│   └── post
│       ├── app       # PostFacade, 글 작성 유스케이스
│       ├── domain    # Post, PostComment, PostMember
│       ├── in        # 초기 데이터, 이벤트 Listener
│       └── out       # PostRepository, PostMemberRepository
├── shared
│   ├── member       # DTO, 회원 이벤트, 원본/복제 회원 기반 클래스, HTTP Client
│   └── post         # DTO, 글/댓글 이벤트
└── global            # 공통 엔티티, 이벤트 발행기, 예외, 응답 객체
~~~

### 이벤트와 HTTP API를 구분한 이유

이벤트는 이미 완료된 작업의 후속 처리를 다른 컨텍스트에 알릴 때 사용합니다. 회원 가입·수정, 글 작성, 댓글 작성처럼 후속 처리와 원본 작업을 느슨하게 결합할 수 있고, 현재 코드는 @TransactionalEventListener(AFTER_COMMIT)으로 원본 트랜잭션이 커밋된 뒤 처리합니다.

HTTP API는 즉시 응답이 필요한 동기 요청에 사용합니다. 글 작성 시 PostWriteUseCase가 MemberApiClient를 통해 다음 회원 API를 호출하고, 응답받은 보안 팁을 글 작성 결과 메시지에 포함합니다.

~~~
GET /api/v1/member/members/randomSecureTip
~~~

따라서 이벤트는 컨텍스트 간 상태 동기화와 후속 작업에, HTTP API는 요청-응답이 필요한 외부 경계 호출에 사용했습니다.

### 회원 복제 흐름

~~~
Member 가입
  └─ MEMBER_MEMBER 저장
  └─ MemberJoinedEvent(MemberDto) 발행
       └─ PostEventListener(AFTER_COMMIT)
            └─ POST_MEMBER에 같은 id로 복제

Post 생성(+3) 또는 댓글 생성(+1)
  └─ MemberEventListener가 원본 회원 activityScore 변경
  └─ MemberModifiedEvent(MemberDto) 발행
       └─ PostEventListener(AFTER_COMMIT)
            └─ POST_MEMBER를 최신 회원 정보로 동기화
~~~

Post와 PostComment는 회원 컨텍스트의 Member를 직접 참조하지 않고 게시글 컨텍스트의 PostMember를 참조합니다. MemberDto에는 비밀번호를 포함하지 않으며, 복제 시에도 비밀번호는 빈 값으로 저장합니다.

이벤트 Listener는 원본 트랜잭션과 분리된 REQUIRES_NEW 트랜잭션으로 동작합니다. 원본 회원과 복제 회원은 같은 회원 id를 사용하지만 서로 다른 테이블에 저장됩니다.

## 3. 확인 결과

검증은 별도 H2 파일 DB와 18080 포트를 사용해 실행한 뒤, 프로세스를 종료하고 H2 Shell로 SQL을 조회했습니다. 일반 실행 시 포트는 8080입니다.

### 3.1 초기 데이터 개수

실행한 SQL:

~~~sql
SELECT 'members' AS table_name, COUNT(*) AS row_count FROM member_member
UNION ALL SELECT 'post_members', COUNT(*) FROM post_member
UNION ALL SELECT 'posts', COUNT(*) FROM post_post
UNION ALL SELECT 'comments', COUNT(*) FROM post_post_comment;
~~~

결과:

~~~
TABLE_NAME   | ROW_COUNT
members      | 6
post_members | 6
posts        | 6
comments     | 8
~~~

초기 데이터는 회원 6명, 복제 회원 6명, 글 6개, 댓글 8개입니다.

### 3.2 회원별 글 수·댓글 수·활동점수

활동점수 정책은 글 작성 1건당 +3, 댓글 작성 1건당 +1입니다.

~~~sql
SELECT m.id,
       m.username,
       (SELECT COUNT(*) FROM post_post p
        WHERE p.author_id = m.id) AS post_count,
       (SELECT COUNT(*) FROM post_post_comment pc
        WHERE pc.author_id = m.id) AS comment_count,
       m.activity_score AS source_score,
       (SELECT pm.activity_score FROM post_member pm
        WHERE pm.id = m.id) AS replica_score
FROM member_member m
ORDER BY m.id;
~~~

결과:

~~~
ID | USERNAME | POST_COUNT | COMMENT_COUNT | SOURCE_SCORE | REPLICA_SCORE
1  | system   | 0          | 0             | 0            | 0
2  | holding  | 0          | 0             | 0            | 0
3  | admin    | 0          | 0             | 0            | 0
4  | user1    | 3          | 2             | 11           | 11
5  | user2    | 2          | 3             | 9            | 9
6  | user3    | 1          | 3             | 6            | 6
~~~

예를 들어 user1은 글 3개와 댓글 2개를 작성했으므로 3 × 3 + 2 × 1 = 11점입니다. 모든 회원에서 원본 점수와 복제 점수가 일치했습니다.

### 3.3 초기화 재실행 시 중복 없음

같은 DB로 애플리케이션을 재실행한 뒤 같은 SQL을 다시 실행했습니다.

~~~
TABLE_NAME   | ROW_COUNT
members      | 6
post_members | 6
posts        | 6
comments     | 8
~~~

기존 데이터가 있으면 각 초기화 로직이 종료되므로 재실행 후에도 회원·복제 회원·글·댓글 수가 증가하지 않았습니다.

### 3.4 실행 로그

애플리케이션 시작과 이벤트 후속 처리에서 다음 로그를 확인했습니다.

~~~
Tomcat started on port 18080 (http) with context path '/'
Started DddMissionApplication
Getting transaction for MemberDataInit.makeBaseMembers
Getting transaction for PostEventListener.handle
Getting transaction for MemberEventListener.handle
Completing transaction for PostEventListener.handle
Completing transaction for MemberEventListener.handle
~~~

AFTER_COMMIT 이벤트가 발생한 뒤 PostEventListener와 MemberEventListener가 별도 트랜잭션으로 실행되는 흐름입니다.

### 3.5 보안 팁 HTTP API

요청:

~~~bash
curl -i http://localhost:8080/api/v1/member/members/randomSecureTip
~~~

응답:

~~~
HTTP/1.1 200
Content-Type: text/plain;charset=UTF-8

비밀번호의 유효기간은 90일 입니다.
~~~

검증 환경에서는 기존 8080 프로세스와 분리하기 위해 18080 포트로 호출했으며, 응답 내용은 동일했습니다.

### 3.6 테스트 결과

~~~
SPRING_DATASOURCE_URL='jdbc:h2:mem:dddmissiontest;MODE=MySQL;DB_CLOSE_DELAY=-1' ./gradlew test

BUILD SUCCESSFUL
1 test completed, 1 passed
~~~

