package app

import (
	"github.com/metacubex/mihomo/tunnel"
)

// NotifyNetworkChanged — явный сигнал «дефолтная сеть системы сменилась» от
// наблюдателя сетей сервисного слоя (см. docs/adr/0001). Внутриядерный монитор
// интерфейса на Android не работает (AutoRoute=false, netlink запрещён),
// поэтому единственный источник сигнала — ConnectivityManager клиента.
// Вся механика реакции (залп, гейт готовности, дебаунс, поколения)
// инкапсулирована в ядре за этим вызовом.
func NotifyNetworkChanged() {
	tunnel.ForceHealthCheckAll()
}
