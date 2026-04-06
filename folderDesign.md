vaadvivaad/                          ← Git repo root
│
├── docker-compose.yml               ← Infrastructure (Postgres, Redis, RabbitMQ)
├── .gitignore                       ← What Git should ignore
├── .env.example                     ← Template for environment variables
├── README.md                        ← Project documentation
│
├── backend/                         ← Spring Boot application
│   ├── pom.xml                      ← Maven build file
│   ├── Dockerfile                   ← Multi-stage Docker build
│   │
│   └── src/
│       ├── main/
│       │   ├── java/
│       │   │   └── com/vaadvivaad/
│       │   │       │
│       │   │       ├── VaadVivaadApplication.java      ← Main class
│       │   │       │
│       │   │       ├── config/                          ← All configuration
│       │   │       │   ├── SecurityConfig.java
│       │   │       │   ├── RabbitMQConfig.java
│       │   │       │   ├── RedisConfig.java
│       │   │       │   ├── WebClientConfig.java
│       │   │       │   └── OpenApiConfig.java
│       │   │       │
│       │   │       ├── common/                          ← Shared across modules
│       │   │       │   ├── exception/
│       │   │       │   │   ├── GlobalExceptionHandler.java
│       │   │       │   │   ├── ResourceNotFoundException.java
│       │   │       │   │   ├── CnrValidationException.java
│       │   │       │   │   └── ErrorResponse.java       ← Record
│       │   │       │   ├── dto/
│       │   │       │   │   └── ApiResponse.java          ← Record
│       │   │       │   └── audit/
│       │   │       │       └── Auditable.java            ← @MappedSuperclass
│       │   │       │
│       │   │       ├── user/                            ← User module
│       │   │       │   ├── controller/
│       │   │       │   │   └── AuthController.java
│       │   │       │   ├── service/
│       │   │       │   │   └── AuthService.java
│       │   │       │   ├── repository/
│       │   │       │   │   └── UserRepository.java
│       │   │       │   ├── entity/
│       │   │       │   │   └── User.java
│       │   │       │   └── dto/
│       │   │       │       ├── RegisterRequest.java      ← Record
│       │   │       │       ├── LoginRequest.java         ← Record
│       │   │       │       └── UserResponse.java         ← Record
│       │   │       │
│       │   │       ├── lookup/                          ← CNR lookup module
│       │   │       │   ├── controller/
│       │   │       │   │   └── CaseLookupController.java
│       │   │       │   ├── service/
│       │   │       │   │   ├── CaseLookupService.java
│       │   │       │   │   └── EcourtsScraper.java
│       │   │       │   ├── repository/
│       │   │       │   │   ├── CourtCaseRepository.java
│       │   │       │   │   └── HearingRepository.java
│       │   │       │   ├── entity/
│       │   │       │   │   ├── CourtCase.java
│       │   │       │   │   └── Hearing.java
│       │   │       │   └── dto/
│       │   │       │       ├── CnrLookupRequest.java     ← Record
│       │   │       │       └── CaseResponse.java         ← Record
│       │   │       │
│       │   │       ├── notification/                    ← Notification module
│       │   │       │   ├── service/
│       │   │       │   │   ├── NotificationService.java
│       │   │       │   │   ├── WhatsAppService.java
│       │   │       │   │   └── SmsService.java
│       │   │       │   ├── listener/
│       │   │       │   │   └── NotificationListener.java  ← @RabbitListener
│       │   │       │   ├── entity/
│       │   │       │   │   └── NotificationLog.java
│       │   │       │   └── repository/
│       │   │       │       └── NotificationLogRepository.java
│       │   │       │
│       │   │       ├── scheduler/                       ← Scheduler module
│       │   │       │   └── service/
│       │   │       │       └── CasePollingScheduler.java  ← @Scheduled cron
│       │   │       │
│       │   │       └── ai/                              ← AI summary module
│       │   │           ├── service/
│       │   │           │   └── ClaudeSummaryService.java
│       │   │           └── dto/
│       │   │               └── SummaryResponse.java      ← Record
│       │   │
│       │   └── resources/
│       │       ├── application.yml                ← Common config
│       │       ├── application-dev.yml            ← Dev profile
│       │       ├── application-prod.yml           ← Prod profile
│       │       └── db/
│       │           └── migration/                 ← Flyway migrations
│       │               ├── V1__create_users_table.sql
│       │               ├── V2__create_court_cases_table.sql
│       │               ├── V3__create_hearings_table.sql
│       │               ├── V4__create_subscriptions_table.sql
│       │               └── V5__create_notification_log_table.sql
│       │
│       └── test/
│           └── java/
│               └── com/vaadvivaad/
│                   ├── VaadVivaadApplicationTests.java
│                   ├── lookup/
│                   │   ├── CaseLookupControllerTest.java
│                   │   └── CourtCaseRepositoryTest.java
│                   └── user/
│                       └── AuthServiceTest.java
│
└── frontend/                        ← React application
    ├── package.json
    ├── vite.config.js
    ├── index.html
    ├── Dockerfile
    │
    ├── public/
    │   └── favicon.ico
    │
    └── src/
        ├── main.jsx                 ← React entry point
        ├── App.jsx                  ← Root component + Router
        │
        ├── api/
        │   └── axios.js             ← Axios instance + JWT interceptor
        │
        ├── hooks/
        │   ├── useAuth.js           ← Custom auth hook
        │   └── useCaseLookup.js     ← Custom case search hook
        │
        ├── context/
        │   └── AuthContext.jsx       ← Auth state provider
        │
        ├── pages/
        │   ├── LoginPage.jsx
        │   ├── RegisterPage.jsx
        │   ├── DashboardPage.jsx
        │   ├── CaseLookupPage.jsx
        │   └── CaseDetailPage.jsx
        │
        ├── components/
        │   ├── Navbar.jsx
        │   ├── CaseCard.jsx
        │   ├── HearingTimeline.jsx
        │   ├── ProtectedRoute.jsx
        │   └── LoadingSpinner.jsx
        │
        └── styles/
            ├── global.css
            ├── Navbar.module.css
            ├── CaseCard.module.css
            └── CaseLookup.module.css