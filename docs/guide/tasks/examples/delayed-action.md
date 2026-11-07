# Отложенное действие

**Задача.** Через сутки после регистрации напомнить пользователю подтвердить почту — если он
к тому времени не подтвердил.

```java
@Transactional
public void register(User user) {
    users.save(user);
    Task<Reminder> reminder = taskService.submit(ConfirmReminder.class, new Reminder(user.id()),
            TaskSubmitOptions.defaults().notBefore(Instant.now().plus(Duration.ofDays(1))));
    user.setReminderTask(reminder.id());
}

@Transactional
public void confirm(User user) {
    user.confirm();
    // напоминание больше не нужно; дело создано при регистрации — окно вокруг неё
    HistoryWindow window = HistoryWindow.between(user.registeredAt().minus(Duration.ofMinutes(5)), Instant.now());
    taskInspection.find(TaskSnapshot.Level.TASK, user.reminderTask(), window)
            .ifPresent(taskService::cancel);
}
```

```java
@Override
public Void process(Task<Reminder> task, ProcessingContext context) {
    User user = users.load(task.payload().userId());
    if (user.confirmed()) {
        context.skip("почта уже подтверждена");
        return null;
    }
    mail.sendReminder(user);
    return null;
}
```

**На что обратить внимание:**

- проверка в процессоре нужна, даже если вы отменяете: подтверждение и запуск напоминания
  могут разминуться на миллисекунды;
- отсрочка точна до такта поллера, а момент сравнивается с часами базы;
- задача, отложенная на месяцы, держит свою партицию от удаления — для дальних сроков храните
  дату у себя и ставьте задачу ближе к делу.
