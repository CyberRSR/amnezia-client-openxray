Amnezia VPN — WWG (двойной VPN) для Windows x64
================================================

Сборка клиента и привилегированного сервиса из ветки wwg-windows-dev
(актуальный upstream amnezia-vpn/amnezia-client + порт Android-реализации WWG).

WWG на Windows работает ровно в том же алгоритме, что и на Android:

  * entry-слой (underlay) — AmneziaWG внутри процесса сервиса, сетевой стек
    gVisor/netstack (awgnetstack.dll); UDP-пакеты exit-слоя идут через него;
  * exit-слой (overlay) — обычный AmneziaWG через tunnel.dll, он владеет
    VPN-интерфейсом Wintun и маршрутом 0.0.0.0/0;
  * защита от петли — entry-сокеты привязываются к физическому адаптеру
    (аналог VpnService.protect из Android);
  * MTU entry-слоя поднимается автоматически под пакет exit-слоя.

Установка
---------

1. Распакуйте архив целиком в любую папку (пути внутри архива важны).
2. Запустите install-service.cmd одним двойным кликом — он запросит права
   администратора. Скрипт:
      - скопирует сборку в "%ProgramFiles%\AmneziaVPN-WWG"
        (можно указать свою папку: install-service.cmd "D:\AmneziaWWG");
      - остановит текущий сервис AmneziaVPN-service и остаточный сервис
        туннеля AmneziaWGTunnel$AmneziaVPN;
      - создаст или перенацелит сервис AmneziaVPN-service на новую сборку
        (автозапуск, LocalSystem, зависимости BFE и nsi);
      - запустит сервис.
   Если до этого стояла официальная версия Amnezia VPN, путь к её сервису
   сохраняется в "%ProgramData%\AmneziaVPN\service-binPath.prev".
3. Запустите "%ProgramFiles%\AmneziaVPN-WWG\AmneziaVPN.exe" обычным
   пользователем (сервис уже поднимает привилегированную часть), импортируйте
   профиль WWG и подключайтесь.

Проверка
--------

  sc query AmneziaVPN-service
  sc query type= all | findstr AmneziaWGTunnel
  notepad "%ProgramData%\AmneziaVPN\log\AmneziaVPN-service.log"
  route print | findstr <IP exit-сервера>

В логе сервиса должны появиться строки "Starting WWG entry layer to ..."
и "WWG entry layer started, relay on 127.0.0.1:<port> mtu <mtu>".
Драйвер Wintun ставится автоматически при первом подключении.

Удаление
--------

uninstall-service.cmd снимает сервисы этой сборки и возвращает на место
предыдущий сервис Amnezia VPN, если он был. Файлы сборки скрипт не трогает.

Сборка из исходников: cmake + conan, recipes/awg-netstack-windows — Go-библиотека
entry-слоя (conan create recipes/awg-netstack-windows --build=missing --no-remote).

--------------------------------------------------------------------------------

Amnezia VPN — WWG (double VPN) for Windows x64
----------------------------------------------

Client plus privileged service built from the wwg-windows-dev branch: current
upstream amnezia-vpn/amnezia-client with the Android WWG implementation ported
to Windows. The entry AmneziaWG layer runs on a gVisor netstack inside the
service (awgnetstack.dll) and carries the exit layer, which owns the Wintun
interface; loop protection pins the entry sockets to the physical adapter, the
Android VpnService.protect equivalent.

Install: unpack the archive, run install-service.cmd elevated. It copies the
build to "%ProgramFiles%\AmneziaVPN-WWG" (pass another folder as the first
argument), stops the existing AmneziaVPN-service and tunnel service, registers
the new one (auto start, LocalSystem, BFE and nsi dependencies) and starts it.
Then start AmneziaVPN.exe as a normal user and import the WWG profile.

Uninstall: uninstall-service.cmd removes these services and re-registers the
previous Amnezia VPN service binary when a backup exists.
