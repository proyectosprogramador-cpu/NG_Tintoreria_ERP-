-- v0.10.4 R3 - realinea correlativos después de restauraciones/backups o datos históricos.
-- No modifica documentos existentes; solo garantiza que el próximo número no choque con uno ya usado.

DO $$
DECLARE v_max bigint; v_last bigint; v_called boolean;
BEGIN
  SELECT COALESCE(MAX(substring(quote_number from '([0-9]+)$')::bigint),0)
    INTO v_max FROM quotations WHERE quote_number ~ '^COT-[0-9]+$';
  SELECT last_value,is_called INTO v_last,v_called FROM quotation_number_seq;
  IF v_max = 0 AND NOT v_called THEN
    PERFORM setval('quotation_number_seq', GREATEST(v_last,1), false);
  ELSE
    PERFORM setval('quotation_number_seq', GREATEST(v_max,v_last), true);
  END IF;
END $$;

DO $$
DECLARE v_max bigint; v_last bigint; v_called boolean;
BEGIN
  SELECT COALESCE(MAX(substring(order_number from '([0-9]+)$')::bigint),0)
    INTO v_max FROM sales_orders WHERE order_number ~ '^PED-[0-9]+$';
  SELECT last_value,is_called INTO v_last,v_called FROM sales_order_number_seq;
  IF v_max = 0 AND NOT v_called THEN
    PERFORM setval('sales_order_number_seq', GREATEST(v_last,1), false);
  ELSE
    PERFORM setval('sales_order_number_seq', GREATEST(v_max,v_last), true);
  END IF;
END $$;

DO $$
DECLARE v_max bigint; v_last bigint; v_called boolean;
BEGIN
  SELECT COALESCE(MAX(substring(production_number from '([0-9]+)$')::bigint),0)
    INTO v_max FROM production_orders WHERE production_number ~ '^OP-[0-9]+$';
  SELECT last_value,is_called INTO v_last,v_called FROM production_order_number_seq;
  IF v_max = 0 AND NOT v_called THEN
    PERFORM setval('production_order_number_seq', GREATEST(v_last,1), false);
  ELSE
    PERFORM setval('production_order_number_seq', GREATEST(v_max,v_last), true);
  END IF;
END $$;
