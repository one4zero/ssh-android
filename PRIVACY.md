# Политика конфиденциальности ClipShell

_Дата вступления в силу: 3 ноября 2026 г._

ClipShell — бесплатный SSH-клиент для Android. Разработчик: Ага Умудханов.

## Коротко

ClipShell **не собирает, не хранит у себя и не передаёт** разработчику или третьим лицам никаких
данных о вас. В приложении нет рекламы, аналитики, отслеживания, учётных записей и встроенных покупок.

## Какие данные хранятся на устройстве

Эти данные остаются только на вашем устройстве, в закрытом хранилище приложения:

- список серверов: адрес, порт, имя пользователя, команда после входа;
- пароли, если вы решили их сохранить, и приватные SSH-ключи с парольными фразами. Они хранятся
  в зашифрованном виде (AES-GCM, ключ шифрования находится в Android Keystore);
- отпечатки ключей серверов (known hosts), история команд, сниппеты и настройки.

Резервное копирование данных приложения в облако отключено. При удалении приложения все данные
удаляются.

## Сеть

Приложение подключается только к тем SSH-серверам, которые вы сами указали. Соединение
шифруется протоколом SSH. Никаких других сетевых запросов приложение не делает.

## Буфер обмена и функция «Поделиться»

Приложение записывает текст в буфер обмена только по вашей команде («Копировать», «Копировать для ИИ»)
и читает буфер только при нажатии «Вставить». Функция «Отправить в ИИ» открывает стандартное меню
Android «Поделиться»: текст передаётся в выбранное вами приложение и дальше обрабатывается по его правилам.

## Разрешения

- **Интернет** — для подключения к вашим SSH-серверам.
- **Фоновая служба и уведомления** — чтобы SSH-сессии не обрывались, пока приложение свёрнуто;
  в уведомлении видно количество открытых сессий.
- **Вибрация** — отклик при нажатии дополнительных клавиш (отключается в настройках).

## Дети

Приложение не предназначено для детей и не собирает никаких данных, в том числе о детях.

## Изменения и контакты

Изменения этой политики публикуются в этом файле. Вопросы можно задать через
[GitHub Issues](https://github.com/one4zero/ssh-android/issues).

---

# ClipShell Privacy Policy

_Effective date: November 3, 2026_

ClipShell is a free SSH client for Android developed by Aga Umudkhanov.

**ClipShell does not collect, store on any server, or share any personal data** with the developer or
third parties. There are no ads, analytics, tracking, accounts or in-app purchases.

**Data stored on the device only**, in the app's private storage: the list of servers (address, port,
user name, startup command); passwords you choose to save and SSH private keys with passphrases, all
encrypted with AES-GCM using a key held in the Android Keystore; trusted server key fingerprints;
command history, snippets and settings. Cloud backup of app data is disabled. All data is deleted
when the app is uninstalled.

**Network:** the app connects only to the SSH servers you configure, over the encrypted SSH protocol.
It makes no other network requests.

**Clipboard and sharing:** text is copied only when you tap a copy action, and the clipboard is read only
when you tap paste. "Send to AI" opens the standard Android share sheet; the text goes to the app you
choose and is handled under that app's policy.

**Permissions:** Internet (connections to your servers); foreground service and notifications (keep SSH
sessions alive in the background); vibration (key feedback, can be disabled).

**Children:** the app is not directed at children and collects no data from anyone.

**Contact:** [GitHub Issues](https://github.com/one4zero/ssh-android/issues).
