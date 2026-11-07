# Плоская задача

```java
@Component
public class ReportProcessor implements FlatTaskProcessor<ReportParams, ReportSummary> {

    @Override
    public String taskType() {
        return "monthly-report";
    }

    @Override
    public ReportSummary process(Task<ReportParams> task, ProcessingContext context) {
        // вся работа здесь; нормальный возврат — успех, исключение — неуспешная попытка
        return new ReportSummary(rows, bytes);
    }
}
```

Задача считается выполненной, когда `process` вернулся без исключения. Возвращённое значение
сохраняется как результат задачи; `null` не сохраняется.
