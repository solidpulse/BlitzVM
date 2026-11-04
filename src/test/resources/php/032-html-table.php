<?php
$users = [
    ["name" => "Alice", "age" => 28, "role" => "Admin"],
    ["name" => "Bob", "age" => 34, "role" => "Editor"],
    ["name" => "Charlie", "age" => 22, "role" => "User"]
];
?>
<table border="1">
  <tr>
    <th>Name</th>
    <th>Age</th>
    <th>Role</th>
  </tr>
<?php foreach ($users as $u) { ?>
  <tr>
    <td><?= htmlspecialchars($u["name"]) ?></td>
    <td><?= $u["age"] ?></td>
    <td><?= htmlspecialchars($u["role"]) ?></td>
  </tr>
<?php } ?>
</table>
