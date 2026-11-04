<?php
$name = htmlspecialchars($_GET['name'] ?? 'World');
header('X-Request-Id: ' . getmypid() . '-' . hrtime(true));
?>
<!doctype html>
<html>
<head><title>Hello from PHP on Truffle</title></head>
<body>
  <h1>Hello, <?= $name ?>!</h1>
  <p>Served by <?= $_SERVER['SERVER_SOFTWARE'] ?> (<?= php_sapi_name() ?>) at <?= date('Y-m-d H:i:s') ?>.</p>
  <ul>
<?php foreach (['method' => $_SERVER['REQUEST_METHOD'], 'uri' => $_SERVER['REQUEST_URI']] as $k => $v): ?>
    <li><?= $k ?>: <?= htmlspecialchars($v) ?></li>
<?php endforeach; ?>
  </ul>
</body>
</html>
