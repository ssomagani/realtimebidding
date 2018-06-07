create table auctions (
    publisher_id integer,
    inventory_id integer,
    imp_type integer,
    bid_floor integer,
    primary key(publisher_id)
);

create table bids (
    inventory_id integer,
    buyer_id integer
);
