<?php
// The syslog facility and option constants live in the standard extension's table.
foreach (['LOG_EMERG', 'LOG_ALERT', 'LOG_CRIT', 'LOG_ERR', 'LOG_WARNING', 'LOG_NOTICE',
             'LOG_INFO', 'LOG_DEBUG', 'LOG_KERN', 'LOG_USER', 'LOG_MAIL', 'LOG_DAEMON',
             'LOG_AUTH', 'LOG_SYSLOG', 'LOG_LPR', 'LOG_NEWS', 'LOG_UUCP', 'LOG_CRON',
             'LOG_AUTHPRIV', 'LOG_LOCAL0', 'LOG_LOCAL1', 'LOG_LOCAL2', 'LOG_LOCAL3',
             'LOG_LOCAL4', 'LOG_LOCAL5', 'LOG_LOCAL6', 'LOG_LOCAL7', 'LOG_PID', 'LOG_CONS',
             'LOG_ODELAY', 'LOG_NDELAY', 'LOG_NOWAIT', 'LOG_PERROR'] as $name) {
    echo $name, '=', constant($name), "\n";
}
var_dump(defined('LOG_USER'), defined('LOG_FTP'));
