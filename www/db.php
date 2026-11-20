<?php
// A typical read API: one connection, a filtered page of rows and a count, returned as JSON.
$categories = ['books', 'games', 'tools', 'music', 'garden'];
$cat = $categories[mt_rand(0, 4)];
$page = mt_rand(0, 9);
$db = mysqli_connect('127.0.0.1', 'root', 'wp', 'bench', 33307);
$c = mysqli_real_escape_string($db, $cat);
$res = mysqli_query($db, "SELECT id, sku, name, price, stock, created_at FROM items WHERE category = '$c' ORDER BY price DESC LIMIT 20 OFFSET " . ($page * 20));
$items = [];
while ($row = mysqli_fetch_assoc($res)) {
    $row['id'] = (int) $row['id'];
    $row['price'] = (float) $row['price'];
    $row['stock'] = (int) $row['stock'];
    $items[] = $row;
}
$total = (int) mysqli_fetch_row(mysqli_query($db, "SELECT COUNT(*) FROM items WHERE category = '$c'"))[0];
mysqli_close($db);
header('Content-Type: application/json');
echo json_encode(['category' => $cat, 'page' => $page, 'total' => $total, 'items' => $items]);
