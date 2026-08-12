Для карт MIFARE Classic
будем обходиться 4 секторами, т.е. 192 байтами. Надо подумать как это лучше сделать.
1) Убираем цифровую подпись, вообще.
2) В первом секторе 2 первых блока - это '"cardId":"UUID v7, генериться на андроиде, по ютс дате и времени"', 2 байта из 3 блока - битовая маска 
    В AsopCardType.kt ровно 14 типов (= ролей карты):
    #	Тип	Роль "идентификатор UUID"
    1	SUPER_ADMIN	root админ   userId
    2	REGION_ADMIN	админ региона  regionId
    3	ORGANIZER_ADMIN	админ организатора organizerId
    4	CARRIER_ADMIN	админ перевозчика carrierId
    5	DISTRIBUTOR_ADMIN	админ дистрибьютора cardsDistributorId
    6	KRS_ADMIN	админ КРС auditServiceId
    7	CARRIER_DISPATCHER	диспетчер перевозчика carrierId
    8	DISTRIBUTOR_DISPATCHER	диспетчер дистрибьютора cardsDistributorId
    9	KRS_DISPATCHER	диспетчер КРС auditServiceId
    10	DRIVER	водитель carrierId
    11	KRS_FOREMAN	бригадир КРС auditServiceId
    12	KRS_CONTROLLER	сотрудник КРС auditServiceId
    13	PASSENGER	пассажир персональный userId
    14	PASSENGER_ANONYMOUS	пассажир анонимный null
    Битовой маски
    - 14 ролей = 14 бит = 2 байта (Int16 / Short).
    - Layout: bit i = (1 shl i) где i = enum ordinal (0..13).
3) Во втором секторе первые 2 блока - это идентификатор UUID, зависящий от битовой маски