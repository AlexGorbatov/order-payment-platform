-- Seed catalog, all prices in EUR cents. DISCONTINUED-MOUSE is kept inactive: old orders may refer to it, new ones
-- cannot.
INSERT INTO product (sku, name, price_minor, currency, active) VALUES
    ('BOOK-CLEAN-CODE',    'Clean Code (paperback)',                  3499, 'EUR', true),
    ('BOOK-DDIA',          'Designing Data-Intensive Applications',   4999, 'EUR', true),
    ('MUG-JAVA',           'Coffee mug "Java"',                       1299, 'EUR', true),
    ('TSHIRT-OPP',         'T-shirt "Order & Payment Platform"',      1999, 'EUR', true),
    ('STICKERS-PACK',      'Sticker pack (10 pcs)',                    499, 'EUR', true),
    ('DISCONTINUED-MOUSE', 'Wired mouse (discontinued)',              1599, 'EUR', false);
