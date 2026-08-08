# AGENTS.md — Android-клиент (Prizrak-Box)

Форк Android-клиента на базе mihomo.

## ⚠️ Ядро живёт в отдельном репозитории

В этом репозитории **нет кода ядра** — только Android-обвязка. Сетевая логика
(прокси, health-check, роутинг, транспорты) находится в отдельном репозитории и
подключается как Go-модуль:

**https://github.com/fUS1ONd/moshen**

```bash
git clone https://github.com/fUS1ONd/moshen.git
```

Правки в поведении соединения, переподключении, проверках живости нод и разборе
конфигов делаются **там**, а не здесь. Здесь — UI, VpnService, сервисный слой и
JNI-мост в ядро (`core/src/main/golang/native/`).

Задача, затрагивающая обе половины, требует двух PR: сначала в ядро, потом сюда
(см. «Порядок выкатки» в конце файла). У ядра свой `AGENTS.md` с его правилами.

## Родословная

```
xuhaoyang/ClashForAndroid  →  …  →  legiz-ru/Prizrak-Box-android  →  fUS1ONd/Prizrak-Box-android (мы)
```

Наш прямой апстрим — **`legiz-ru/Prizrak-Box-android`**. Он же ведёт и форк ядра
(`legiz-ru/moshen`), то есть обе половины проекта берутся у него.

## Ветки

| Ветка | Что это | Правила |
|---|---|---|
| `main` | наша рабочая ветка, default | сюда мержим фичи, отсюда собираются релизы |
| `upstream-base` | точный слепок релиза апстрима | **никогда не коммитить**, только переставлять на новый релиз апстрима |

Фичи — в ветках `feat/*` от `main`.

## Как обновиться до нового релиза апстрима

У легиза ветки называются как попало (`prizrak`, `claude/*`, протухший `main`),
ориентироваться надо на **страницу Releases**.

```bash
git fetch upstream --tags
git checkout upstream-base && git reset --hard <коммит-релиза-апстрима>
git push --force-with-lease origin upstream-base
git checkout main && git rebase upstream-base
git push --force-with-lease origin main
```

## Как подключено ядро

`replace` в **двух** файлах — `core/src/main/golang/go.mod` и
`core/src/foss/golang/go.mod`:

```
replace github.com/metacubex/mihomo => github.com/fUS1ONd/moshen <тег>
```

Путь модуля в самом ядре остаётся `github.com/metacubex/mihomo` — так и должно
быть, переименовывать его нельзя.

**Бампить версию ядра руками не надо.** Есть workflow
`Update Moshen Core and Go Modules` (вкладка Actions → Run workflow): он берёт
последний релиз ядра (или указанный тег), правит оба `go.mod`, прогоняет
`go mod tidy` и открывает PR.

## Релизы

| Что | Как запускается | Что получается |
|---|---|---|
| Pre-release APK | автоматически на каждый пуш в `main` | релиз с тегом `Prerelease-alpha`, перезаписывается; тело — ченджлог с прошлого пререлиза |
| Релиз APK | Actions → `Build Release` → указать тег (`v1.2.3`) | бамп версии в `build.gradle.kts`, коммит, тег, GitHub Release с APK |

Ссылка для скачивания пользователями — страница Releases репозитория.
### Откуда берётся версия

`versionName` и `versionCode` в `defaultConfig` корневого `build.gradle.kts` —
обычные константы (сейчас `1.1` и `101`, достались от апстрима). Из них же
собирается имя файла: `archivesBaseName = "prizrak-box-$versionName"`, а флейвор
добавляет суффикс (`.Alpha`, `.Meta`).

Два режима сборки обходятся с ними по-разному:

- **Pre-release** не трогает `versionName` (все альфы остаются `1.1`, тег
  `Prerelease-alpha` каждый раз перезаписывается), но **`versionCode` считает в
  CI**: `100000 + GITHUB_RUN_NUMBER`. Значение workflow дописывает в
  `local.properties` ключом `alpha.version.code`, а alpha-флейвор перекрывает им
  константу. Смещение `100000` обязательно — голый номер прогона меньше `101` и
  система сочла бы установку откатом. Вне CI ключа нет, и локальная alpha-сборка
  получает `101` из `defaultConfig`.
- **Build Release** переписывает обе константы из введённого тега
  (`advance-android-version-actions`), коммитит `Bump version to ...` и ставит тег.
  Только здесь версия действительно выводится из тега.

Две ловушки:

- Тег обязан иметь **ровно три компонента** (`v1.2.3`). `versionCode` считается как
  `printf "%1d%02d%03d"` по трём частям: введёшь `v1.2` — третьей части не будет,
  `printf` получит пустую строку вместо числа и посчитает код мусорно, без внятной
  ошибки.
- `versionCode` должен только расти **внутри своего канала**: Android ставит
  обновление поверх лишь при большем или равном коде. Каналы независимы (это
  разные applicationId), сравнивать `100xxx` альфы с `101000+` мет бессмысленно.
  Правя схему нумерации alpha, следи, чтобы новые коды не оказались ниже уже
  выпущенных.

В ядре (`fUS1ONd/moshen`) устроено иначе — там константы версии нет вообще, её
целиком вычисляет CI.

### Встроенный апдейтер

Приложение само проверяет обновления (`app/src/main/java/com/github/kr328/clash/update/`)
и ходит за ними в **этот** репозиторий, `fUS1ONd/Prizrak-Box-android`. Канал задан
флейвором через `buildConfigField("String", "UPDATE_CHANNEL", ...)`: `meta` берёт
`/releases/latest`, `alpha` — релиз по тегу `Prerelease-alpha`. Фоновая проверка —
раз в 12 ч для meta и раз в 72 ч для alpha. Решение «обновляться ли и какой файл
качать» принимает чистая функция `decideUpdate` в `UpdateDecision.kt` по
`output-metadata.json` из ассетов релиза (код версии, applicationId, имя файла под
ABI устройства, фолбэк — универсальная сборка). Термины — в разделе «Обновление
приложения» `CONTEXT.md`, обоснование — в `docs/adr/0002-kanaly-obnovleniy.md`.

Что это значит для правок релизных workflow:

- `output-metadata.json` обязан попадать в ассеты релиза рядом с APK — без него
  апдейтер не видит обновления вообще.
- Тело релиза показывается пользователю как ченджлог (обрезается на 2000 символах),
  так что автогенерируемый список всех PR туда класть нельзя.
- Имена файлов APK не выдумывать: апдейтер берёт их из метаданных, а не по маске.

### Секреты для подписи (обязательны)

Без них сборка релиза падает на шаге подписи. Settings → Secrets and variables →
Actions → Secrets:

| Секрет | Что это |
|---|---|
| `KEYSTORE_BASE64` | keystore, закодированный в base64 |
| `SIGNING_STORE_PASSWORD` | пароль хранилища |
| `SIGNING_KEY_ALIAS` | алиас ключа |
| `SIGNING_KEY_PASSWORD` | пароль ключа |

### ⚠️ Свой keystore — обязательно

Унаследованный от апстрима `release.keystore` **удалён из репозитория**: он лежал
в открытом виде, и подписанные им сборки мог выпустить кто угодно.

Свой ключ генерится один раз и в репозиторий не попадает (`.gitignore` его ловит):

```bash
keytool -genkeypair -v -keystore release.keystore -alias prizrak \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore   # значение для секрета KEYSTORE_BASE64
```

Ключ определяет совместимость обновлений: **сменишь его после первого публичного
релиза — пользователи не смогут обновиться поверх, только переустановкой с
потерей данных**. Сгенерировать и положить в секреты надо до первой публикации,
а сам файл — сохранить в надёжном месте, восстановить его нельзя.

## Сборка локально

```bash
./gradlew app:assembleMetaDebug     # debug-сборка, подпись отладочная
./gradlew app:assembleMetaRelease   # release; без signing.properties тоже уйдёт на debug-подпись
```

Флейворы: `Meta` — обычные релизы, `Alpha` — pre-release сборки.

## Порядок выкатки изменения, затрагивающего ядро

1. В `fUS1ONd/moshen`: смержить фичу в `main`, поставить тег, дождаться релиза.
2. Здесь: Actions → `Update Moshen Core and Go Modules` → смержить PR.
3. Пуш в `main` соберёт pre-release APK для проверки.
4. Actions → `Build Release` с новым тегом — публичный релиз.

## Agent skills

Репозиторий настроен под инженерные скиллы (`/setup-matt-pocock-skills`).

### Issue tracker

Задачи и спеки живут локальными markdown-файлами в `.scratch/<feature>/` — ни GitHub Issues,
ни внешний трекер не используются. См. `docs/agents/issue-tracker.md`.

### Triage labels

Пять канонических ролей без переименований (`needs-triage`, `needs-info`, `ready-for-agent`,
`ready-for-human`, `wontfix`). См. `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` и `docs/adr/` в корне репозитория. См. `docs/agents/domain.md`.

## История

Первая реализация быстрого переключения при смене сети (прототип) в репозиторий
не вошла. Бэкап и патчи — вне репозитория, в `~/fork-backup-20260808/` на сервере
`home`. Переносить прототип в `main` как есть не следует, фича переделывается
системно.
