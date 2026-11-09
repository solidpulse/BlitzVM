const timers = require('node:timers/promises');

module.exports = async (req, res) => {
    const ms = Number(req.query.ms || 500);
    await timers.setTimeout(ms);
    res.end('slept\n');
};
