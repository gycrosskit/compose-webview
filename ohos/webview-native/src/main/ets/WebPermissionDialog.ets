export interface WebPermissionDialogOptions {
  title: string;
  message: string;
  cancel: string;
  confirm: string;
  complete: (accepted: boolean) => void;
}

@Builder
export function createWebPermissionDialog(options: WebPermissionDialogOptions) {
  Column({ space: 20 }) {
    Text(options.title).fontSize(20).fontWeight(FontWeight.Medium)
    Text(options.message).fontSize(16)
    Row({ space: 16 }) {
      Button(options.cancel).onClick(() => options.complete(false))
      Button(options.confirm).onClick(() => options.complete(true))
    }.justifyContent(FlexAlign.End).width('100%')
  }.padding(24).width('90%').borderRadius(24)
    .backgroundColor($r('sys.color.ohos_id_color_dialog_bg'))
}
