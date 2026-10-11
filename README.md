# Round Timer (Fabric 1.21.1)

## Сборка
Нужны JDK 21 и Gradle 8.8+ (или gradlew из любого Fabric-шаблона).

    gradle build

Готовый мод: build/libs/round-timer-1.0.0.jar
Положить в папку mods сервера. Нужен Fabric Loader 0.16+ и Fabric API для 1.21.1.
Клиентам мод ставить не нужно.

## Команды (start/add/stop/spec — только для операторов, /timer — для всех)
/timer start <мин>, /timer add <мин>, /timer stop,
/timer spec on|off|stop, /timer
