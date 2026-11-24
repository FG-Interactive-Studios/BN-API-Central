-- Fresh installs already have a disabled V7 Sonar Retro row but no managed revision.
-- Existing installations with revision 1 are upgraded by catalog bootstrap.
UPDATE store_items
SET enabled=TRUE, description='Quatro efeitos retrô em 8 bits: acerto, água e afundamentos. Preço de demonstração.'
WHERE id='sound_sonar'
  AND NOT EXISTS (SELECT 1 FROM store_catalog_managed WHERE item_id='sound_sonar');
