# AHCC — адресаты сообщений

## Где добавлять и удалять

Единственное место в MVP:

`AndroidHermesCloudChat/app/src/main/java/com/nous/ahcc/domain/model/Addressee.kt`

Редактируйте список **`Addressee.catalog`**:

```kotlin
val catalog: List<Addressee> = listOf(
    Addressee(LIAISON_ID, "Gate"),           // id liaison — без строки TO:
    Addressee("english_tutor", "EnglishTutor"),
    Addressee("my_skill", "MySkill"),        // пример нового адресата
)
```

- **`id`** — стабильный ключ (латиница, snake_case).
- **`displayName`** — подпись в dropdown и в первой строке исходящего текста: `TO: displayName`.

После изменения списка пересоберите и установите AHCC. В **Settings → Connection** показаны текущие адресаты только для справки.

Позже планируется синхронизация с Dashboard / редактирование в настройках без пересборки.

## Поведение

| Адресат | Исходящий текст в Telegram |
|---------|----------------------------|
| Gate (`liaison`) | Как введено пользователем |
| Любой другой | `TO: EnglishTutor` + перевод строки + текст |

Команды **завершить / закончить / стоп / хватит** (без учёта регистра) после отправки возвращают выбор на **Gate**.
