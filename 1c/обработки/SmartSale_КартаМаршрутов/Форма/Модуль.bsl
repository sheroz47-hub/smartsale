// Модуль управляемой формы внешней обработки «SmartSale: карта клиентов».
//
// АРМ супервайзера: показывает клиентов выбранного агента на карте (маркеры по
// координатам из контактной информации контрагента — том же источнике, что
// отдаёт /customers и куда пишет уточнение координат с телефона).
//
// Карта — Поле HTML-документа с Leaflet (загружается с CDN) и тайлами
// OpenStreetMap. Данные точек ВШИТЫ в HTML при формировании на сервере —
// рантайм-моста 1С↔JS нет, поэтому надёжно. Требует интернета на рабочем
// месте (тайлы и библиотека) и ТОНКОГО клиента.
//
// Построение маршрута — целиком в JS страницы (без обращений в 1С): клик по
// точке добавляет её в маршрут по порядку, рисуется линия с длиной, ссылки
// «Открыть в Яндекс/Google Картах» дают реальную навигацию по выбранным точкам.
// Линия — прямая между точками (не по дорогам); длина по дорогам — во внешних
// картах по ссылке.
//
// Реквизиты формы (см. README):
//   Агент       - СправочникСсылка.Пользователи
//   ПолеБраузер - Строка (HTML карты, HTMLDocumentField)
//   Лог         - Строка (ТолькоПросмотр)

#Область ОбработчикиКомандФормы

&НаКлиенте
Процедура ПоказатьНаКарте(Команда)

	Если НЕ ЗначениеЗаполнено(Агент) Тогда
		ПоказатьПредупреждение(, "Укажите агента.");
		Возврат;
	КонецЕсли;
	ПоказатьНаКартеНаСервере();

КонецПроцедуры

#КонецОбласти

#Область СлужебныеПроцедуры

&НаСервере
Процедура ПоказатьНаКартеНаСервере()

	Запрос = Новый Запрос;
	Запрос.УстановитьПараметр("Агент", Агент);
	Запрос.Текст =
	"ВЫБРАТЬ
	|	П.Наименование КАК Наименование,
	|	МАКСИМУМ(ВЫБОР КОГДА КИ.Вид.ИдентификаторДляФормул = ""ГеографическаяШирота""
	|			ТОГДА КИ.Представление ИНАЧЕ """" КОНЕЦ) КАК Широта,
	|	МАКСИМУМ(ВЫБОР КОГДА КИ.Вид.ИдентификаторДляФормул = ""ГеографическаяДолгота""
	|			ТОГДА КИ.Представление ИНАЧЕ """" КОНЕЦ) КАК Долгота
	|ИЗ
	|	Справочник.Партнеры КАК П
	|		ЛЕВОЕ СОЕДИНЕНИЕ Справочник.Контрагенты.КонтактнаяИнформация КАК КИ
	|		ПО КИ.Ссылка.Партнер = П.Ссылка
	|			И КИ.Вид.ИдентификаторДляФормул В (""ГеографическаяШирота"", ""ГеографическаяДолгота"")
	|ГДЕ
	|	П.Клиент
	|	И НЕ П.ПометкаУдаления
	|	И П.ОсновнойМенеджер = &Агент
	|СГРУППИРОВАТЬ ПО
	|	П.Ссылка,
	|	П.Наименование
	|УПОРЯДОЧИТЬ ПО
	|	П.Наименование";

	Точки = Новый Массив;
	ВсегоКлиентов = 0;
	Выборка = Запрос.Выполнить().Выбрать();
	Пока Выборка.Следующий() Цикл
		ВсегоКлиентов = ВсегоКлиентов + 1;
		Если ПустаяСтрока(Выборка.Широта) Или ПустаяСтрока(Выборка.Долгота) Тогда
			Продолжить;
		КонецЕсли;
		Точка = Новый Структура;
		Точка.Вставить("lat", СокрЛП(Выборка.Широта));
		Точка.Вставить("lon", СокрЛП(Выборка.Долгота));
		Точка.Вставить("name", Выборка.Наименование);
		Точки.Добавить(Точка);
	КонецЦикла;

	Запись = Новый ЗаписьJSON;
	Запись.УстановитьСтроку();
	ЗаписатьJSON(Запись, Точки);
	ТочкиJSON = Запись.Закрыть();

	ПолеБраузер = HTMLКарты(ТочкиJSON);
	Лог = "Клиентов у агента: " + ВсегоКлиентов
		+ ", с координатами (на карте): " + Точки.Количество() + "."
		+ ?(Точки.Количество() = 0,
			" Координаты уточняются агентом на визите — точек пока нет.",
			" Кликайте точки в порядке объезда — построится маршрут; «Сбросить» очищает.");

КонецПроцедуры

// Готовая HTML-страница карты с вшитым массивом точек. Leaflet и тайлы — из
// интернета; точки уже внутри страницы, без вызовов из 1С.
Функция HTMLКарты(Знач ТочкиJSON)

	HTML = "<!DOCTYPE html>
	|<html><head><meta charset=""utf-8"">
	|<link rel=""stylesheet"" href=""https://unpkg.com/leaflet@1.9.4/dist/leaflet.css""/>
	|<script src=""https://unpkg.com/leaflet@1.9.4/dist/leaflet.js""></script>
	|<style>
	|html,body,#map{height:100%;margin:0;padding:0}
	|.panel{position:absolute;top:8px;right:8px;z-index:1200;background:#fff;
	|  padding:8px 10px;border-radius:6px;box-shadow:0 1px 5px rgba(0,0,0,.35);
	|  font:13px Arial,sans-serif;max-width:240px}
	|.panel b{color:#c0392b}
	|.panel a{display:none;margin-top:6px;color:#2c6fbb;text-decoration:none}
	|.panel button{margin-top:8px;font:13px Arial;cursor:pointer}
	|.leaflet-tooltip.ord{background:#c0392b;color:#fff;border:0;font-weight:bold;
	|  padding:0 6px;border-radius:9px}
	|.leaflet-tooltip.ord:before{display:none}
	|</style>
	|</head><body>
	|<div id=""map""></div>
	|<div class=""panel"">
	|  <div id=""info"">Кликайте точки в порядке объезда</div>
	|  <a id=""yandex"" target=""_blank"" rel=""noopener"">Открыть в Яндекс.Картах</a>
	|  <a id=""google"" target=""_blank"" rel=""noopener"">Открыть в Google Картах</a>
	|  <div><button id=""reset"">Сбросить маршрут</button></div>
	|</div>
	|<script>
	|var pts = %ТОЧКИ%;
	|var map = L.map('map').setView([41.311,69.240],11);
	|L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,
	|  attribution:'© OpenStreetMap'}).addTo(map);
	|var markers=[], sel=[], line=null;
	|function dist(a,b){
	|  var R=6371000, rad=Math.PI/180;
	|  var dLa=(b[0]-a[0])*rad, dLo=(b[1]-a[1])*rad;
	|  var s=Math.sin(dLa/2)*Math.sin(dLa/2)+Math.cos(a[0]*rad)*Math.cos(b[0]*rad)*
	|    Math.sin(dLo/2)*Math.sin(dLo/2);
	|  return 2*R*Math.asin(Math.sqrt(s));
	|}
	|function redraw(){
	|  markers.forEach(function(m){ if(m.getTooltip()) m.unbindTooltip(); });
	|  if(line){ map.removeLayer(line); line=null; }
	|  var coords=[], total=0;
	|  sel.forEach(function(m,i){
	|    m.bindTooltip(String(i+1),{permanent:true,direction:'top',className:'ord'});
	|    m.openTooltip();
	|    coords.push(m._ll);
	|  });
	|  for(var i=1;i<coords.length;i++){ total+=dist(coords[i-1],coords[i]); }
	|  if(coords.length>1){
	|    line=L.polyline(coords,{color:'#c0392b',weight:4,opacity:0.85}).addTo(map);
	|  }
	|  var info=document.getElementById('info');
	|  info.innerHTML = sel.length ?
	|    ('Точек: <b>'+sel.length+'</b>, длина <b>'+(total/1000).toFixed(1)+
	|      ' км</b><br><small>прямая между точками; по дорогам — по ссылке</small>') :
	|    'Кликайте точки в порядке объезда';
	|  var yl=document.getElementById('yandex'), gl=document.getElementById('google');
	|  if(sel.length>=2){
	|    var seq=sel.map(function(m){return m._ll[0]+','+m._ll[1];});
	|    yl.href='https://yandex.ru/maps/?rtext='+seq.join('~')+'&rtt=auto';
	|    gl.href='https://www.google.com/maps/dir/'+seq.join('/');
	|    yl.style.display='block'; gl.style.display='block';
	|  } else { yl.style.display='none'; gl.style.display='none'; }
	|}
	|pts.forEach(function(p){
	|  var la=parseFloat(p.lat), lo=parseFloat(p.lon);
	|  if(isNaN(la)||isNaN(lo)) return;
	|  var m=L.marker([la,lo]).addTo(map);
	|  m._ll=[la,lo];
	|  m.bindPopup(p.name);
	|  m.on('click',function(){
	|    var k=sel.indexOf(m);
	|    if(k>=0){ sel.splice(k,1); } else { sel.push(m); }
	|    redraw();
	|  });
	|  markers.push(m);
	|});
	|function fitAll(tries){
	|  if(!markers.length) return;
	|  map.invalidateSize();
	|  if(map.getSize().x>0){
	|    map.fitBounds(L.featureGroup(markers).getBounds().pad(0.2));
	|  } else if(tries>0){
	|    setTimeout(function(){ fitAll(tries-1); },150);
	|  }
	|}
	|fitAll(40);
	|document.getElementById('reset').onclick=function(){ sel=[]; redraw(); };
	|</script></body></html>";

	Возврат СтрЗаменить(HTML, "%ТОЧКИ%", ТочкиJSON);

КонецФункции

#КонецОбласти
