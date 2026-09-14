// @ts-strict-ignore
import {Component} from "@angular/core";
import {FormControl, FormGroup, Validators} from "@angular/forms";
import { AbstractModal } from "src/app/shared/components/modal/abstractModal";
import {ChannelAddress, CurrentData, Utils} from "src/app/shared/shared";

@Component({
  templateUrl: "./modal.html",
  standalone: false,
})
export class ModalComponent extends AbstractModal {
  public propertyMode: string;

  public readonly CONVERT_MANUAL_ON_OFF = Utils.CONVERT_MANUAL_ON_OFF(this.translate);
  public readonly CONVERT_WATT_TO_KILOWATT = Utils.CONVERT_WATT_TO_KILOWATT;
  public readonly CONVERT_DATE = Utils.CONVERT_DATE;

  protected override getChannelAddresses(): ChannelAddress[] {
    return [
      new ChannelAddress(this.component.id, "_PropertyMode"),
      new ChannelAddress(this.component.id, "_PropertyStartTime"),
      new ChannelAddress(this.component.id, "_PropertyEndTime"),
      new ChannelAddress(this.component.id, "_PropertyMaxChargePower"),
      new ChannelAddress(this.component.id, "_PropertyMaxDischargePower"),
    ];
  }

  protected override onCurrentData(currentData: CurrentData) {
    this.propertyMode = currentData.allComponents[this.component.id + "/_PropertyMode"];
  }

  protected override getFormGroup(): FormGroup {
    return this.formBuilder.group({
      mode: new FormControl(this.component.properties.mode),
      maxChargePower: new FormControl(this.component.properties.maxChargePower, [Validators.required, Validators.min(0)]),
      maxDischargePower: new FormControl(this.component.properties.maxDischargePower, [Validators.required, Validators.min(0)]),
      startTime: new FormControl(this.component.properties.startTime),
      endTime: new FormControl(this.component.properties.endTime),
    });
  }
}
