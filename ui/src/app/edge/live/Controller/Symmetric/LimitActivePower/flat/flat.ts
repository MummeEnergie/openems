// @ts-strict-ignore
import {Component} from "@angular/core";
import {AbstractFlatWidget} from "src/app/shared/components/flat/abstract-flat-widget";
import {ChannelAddress, CurrentData, Utils} from "src/app/shared/shared";

import {ModalComponent} from "../modal/modal";

@Component({
  selector: "Controller_LimitActivePowerSymmetric",
  templateUrl: "./flat.html",
  standalone: false,
})
export class FlatComponent extends AbstractFlatWidget {

  public propertyMode: string;
  public uiEnabled: boolean;

  public readonly CONVERT_MANUAL_ON_OFF = Utils.CONVERT_MANUAL_ON_OFF(this.translate);
  public readonly CONVERT_WATT_TO_KILOWATT = Utils.CONVERT_WATT_TO_KILOWATT;
  public readonly CONVERT_DATE = Utils.CONVERT_DATE;

  async presentModal() {
    if (!this.isInitialized) {
      return;
    }
    const modal = await this.modalController.create({
      component: ModalComponent,
      componentProps: {
        component: this.component,
      },
    });
    return await modal.present();
  }

  protected override getChannelAddresses(): ChannelAddress[] {
    return [
      new ChannelAddress(this.component.id, "_PropertyMode"),
      new ChannelAddress(this.component.id, "_PropertyStartTime"),
      new ChannelAddress(this.component.id, "_PropertyEndTime"),
      new ChannelAddress(this.component.id, "_PropertyMaxChargePower"),
      new ChannelAddress(this.component.id, "_PropertyMaxDischargePower"),
      new ChannelAddress(this.component.id, "_PropertyUiEnabled"),
    ];
  }

  protected override onCurrentData(currentData: CurrentData) {
    this.propertyMode = currentData.allComponents[this.component.id + "/_PropertyMode"];
    this.uiEnabled = currentData.allComponents[this.component.id + "/_PropertyUiEnabled"];
  }

}
