package com.osrstracker.model;

import java.util.List;
import lombok.Value;

/** protocol.ts BankDepositData: items put into the bank through a deposit box. */
@Value
public class BankDepositData
{
	List<Item> items;
}
