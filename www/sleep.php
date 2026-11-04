<?php
// Sleeping gives up the execution slot, so other requests run meanwhile.
usleep((int)($_GET['ms'] ?? 500) * 1000);
echo "slept\n";
