<?php
// Never terminates on its own: the scheduler preempts it every quantum and
// max_execution_time eventually cancels it.
while (true) {
}
