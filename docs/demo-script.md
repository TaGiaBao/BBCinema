# Kịch bản demo BBCinema (bảo vệ đồ án)

Thời lượng gợi ý: 15–20 phút. Thứ tự đi từ khách hàng → quầy vé → quản trị → backend, đúng như luồng
dữ liệu thật: khách đặt vé, nhân viên soát vé, quản trị xem doanh thu, rồi mở code để giải thích.

## 0. Chuẩn bị (trước giờ bảo vệ 15 phút)

| Việc | Cách làm |
|---|---|
| Dữ liệu sạch | Tắt app đang chạy, chạy `scripts\reset-demo.cmd` (cmd) hoặc `.\scripts\reset-demo.cmd` (PowerShell). Đợi dòng `Started BbCinemaApplication`. |
| Trình duyệt 1 (Chrome thường) | Khách: `http://localhost:8080` |
| Trình duyệt 2 (cửa sổ ẩn danh) | Người khách thứ hai, để demo sơ đồ ghế tự cập nhật |
| Trình duyệt 3 (Edge) | Nội bộ: `http://localhost:8080/internal/login` |
| Điện thoại | Để mở vé có mã QR, đưa vào webcam lúc soát vé |
| IntelliJ / VS Code | Mở sẵn các file ở mục 5 |
| VNPay | Trang đăng ký sandbox của VNPay (`/devreg/`) đã đóng (20/11/2026), nên không có mã riêng. Demo bằng *Thanh toán thử*; phần VNPay trình bày ở mục 5. Nếu sau này có mã thì ghi vào `.env` (`VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`). |

Tài khoản: `user@bbcinema.vn / User@123`, `teen@bbcinema.vn / Teen@123` (14 tuổi),
`staff@bbcinema.vn / Staff@123`, `admin@bbcinema.vn / Admin@123`. Mã giảm giá: `BBCINEMA10`, `THANHVIEN20`, `HETHAN`.

## 1. Khách chưa đăng nhập (2 phút)

1. Mở trang chủ lần đầu → **hộp thoại chọn rạp**. Bấm *Dùng vị trí của tôi* (hoặc chọn tay).
   > "Lần đầu vào, hệ thống hỏi khách muốn xem ở rạp nào; lựa chọn lưu trong cookie."
2. Banner, 3 tab phim. Trỏ vào poster: **nhãn tuổi góc trên trái, điểm đánh giá góc dưới phải**.
3. Bấm **VIE → ENG**: toàn bộ giao diện, cả banner, chuyển sang tiếng Anh. Chuyển lại VIE.
4. Menu **Rạp** → bản đồ các rạp, nút Chỉ đường.
5. Bấm **Mua vé** trên một phim → hộp thoại 14 ngày, giờ chiếu nhóm theo 2D/3D và ngôn ngữ.

## 2. Thành viên đặt vé (5 phút) – phần quan trọng nhất

1. Đăng nhập `user@bbcinema.vn`. (Có thể nhắc: còn có *Đăng nhập bằng Google* và *Quên mật khẩu*.)
2. Chọn một suất → **sơ đồ ghế**: A–E thường, F–I VIP, J ghế đôi (giá = 2 ghế VIP).
3. **Demo sơ đồ tự cập nhật**: ở trình duyệt 2 (ẩn danh, đăng nhập `teen@...` với phim P hoặc tạo tài khoản),
   mở cùng suất. Trình duyệt 1 chọn G5, G6 rồi bấm *Tiếp tục* → sau ~3 giây trình duyệt 2 thấy G5, G6 thành
   "đang có người giữ".
   > "Màn hình chỉ là lớp tiện lợi; lớp bảo vệ thật là khoá dòng trong CSDL (mục 5)."
4. Bắp nước → thanh toán: nhập `BBCINEMA10`, dùng 10 điểm, xem tổng tiền tính lại. Thử `HETHAN` để thấy báo lỗi.
5. Chọn **Chuyển khoản QR** → trang hiện mã VietQR, số tiền, nội dung `BBC <mã vé>`, đếm ngược giữ ghế.
   - Mở app ngân hàng trên điện thoại, quét mã: app hiện đúng tài khoản, số tiền, nội dung.
   - Có SePay (`SEPAY_API_TOKEN` trong `.env`): chuyển thật, vài giây sau trang **tự chuyển sang vé**.
   - Không muốn chuyển tiền thật: bấm **Giả lập ngân hàng báo có** (chỉ có ở bản demo).
   > "Hệ thống hỏi SePay mỗi 5 giây; giao dịch nào có nội dung BBC + mã vé và đủ tiền thì vé được xác nhận. Ô VNPay bật được khi rạp có mã của VNPay."
6. **Vé điện tử**: mã QR, logo mờ phía sau, nút In vé. Bấm In vé để thấy bản in.
7. Mở **Hộp thư demo** (`/demo/mailbox`): email vé có QR vừa gửi.
   > "Máy demo không có tài khoản Gmail, nên hệ thống giữ bản sao email để xem. Chế độ này tự tắt khi triển khai thật."
8. Trang **Thành viên**: hạng thành viên, số tiền còn thiếu để lên hạng; lịch sử vé; lịch sử điểm.
9. (Nếu kịp) **Giới hạn tuổi**: đăng nhập `teen@...` đặt phim T18 → bị từ chối, nêu rõ tuổi.

## 3. Quầy vé (3 phút)

1. Trình duyệt 3: `/internal/login` bằng `staff@...`.
   > "Khách và nội bộ tách hẳn: trang khách không có link nào sang đây; tài khoản khách không vào được."
   Hệ thống bắt **vào ca**: quản lý nhập quỹ đầu ca (ví dụ 1.000.000đ) và `admin@bbcinema.vn / Admin@123` để mở ca.
   > "Nhân viên chỉ được thu tiền vào: không có nút đăng xuất, không tự hoàn tiền; mở ca, hoàn tiền, kết ca đều cần quản lý."
2. **Bán vé tại quầy**: chọn suất, chọn ghế, chọn hình thức thanh toán:
   - **Tiền mặt**: nhập số tiền khách đưa (hoặc bấm mệnh giá gợi ý) → hiện tiền thừa trả khách hoặc số còn thiếu.
   - **Thẻ ngân hàng**: nhập mã chuẩn chi và số tiền trên hoá đơn máy POS (phải khớp tổng tiền).
   - **Chuyển khoản QR**: màn hình quầy hiện mã cho khách quét; tiền về thì tự in vé, hoặc nhân viên bấm *Đã nhận tiền*.
3. **Soát vé**: bấm *Bật camera*, đưa mã QR trên điện thoại (vé ở bước 2.6) vào webcam → hiện thông tin vé →
   *Xác nhận vào phòng*. Soát lại lần 2 → báo đã soát.
4. **Hoàn tiền**: tra một vé chưa soát → *Khách muốn huỷ vé* → quản lý nhập tài khoản → tiền hoàn trừ vào két của ca.
5. **Kết ca**: bấm *Kết ca* (thay cho nút Đăng xuất) → xem tiền thu trong ca, tiền phải có trong két → quản lý nhập số
   tiền đếm được và tài khoản → hệ thống báo két khớp/thừa/thiếu, in biên bản ca và tự đăng xuất.
   > "Mỗi ca chốt tiền riêng: quỹ đầu ca giữ lại, chỉ tính các khoản thu sau khi mở ca, không lẫn với ca sau."

## 4. Quản trị (4 phút)

1. Đăng xuất, đăng nhập `admin@...`.
2. **Thống kê**: doanh thu hôm nay, tháng, biểu đồ theo ngày, theo phim. **Ca bán vé**: danh sách ca, chênh lệch két, biên bản.
3. **Rạp và phòng** → mở một phòng: trình chỉnh sơ đồ ghế kéo thả.
4. **Suất chiếu → Tạo lịch tự động**: chọn rạp, một ngày sau 15 ngày tới, bấm *Tạo lịch đề xuất*.
   > "Phim chia 3 hạng theo tuần chiếu (45/35/20% số suất), neo giờ vàng ở phòng lớn nhất, các phòng lệch giờ ít nhất 10 phút,
   > dọn phòng tính theo số ghế."
5. **Bảng giá vé**: giá ghế đôi không nhập tay, tự bằng 2 ghế VIP.
6. **Đánh giá phim**: ẩn một đánh giá → điểm trung bình của phim cập nhật.
7. **Banner**: sửa tiêu đề tiếng Anh → về trang chủ bấm ENG để thấy.

## 5. Backend (4 phút)

| Nội dung | Mở ở đâu |
|---|---|
| REST API, thử gọi `GET /api/movies` | Swagger: `/swagger-ui.html` |
| Khoá dòng `SELECT ... FOR UPDATE` | `ShowtimeSeatRepository.findForUpdate` |
| Giữ ghế trong giao dịch | `BookingService.holdSeats`, `lockAndHoldSeats` |
| 8 người tranh 1 ghế | `BookingFlowIntegrationTest.onlyOneOfManyConcurrentBuyersGetsTheSeat` → bấm Run |
| Tự trả ghế quá hạn | `ExpiredHoldScheduler`, `BookingService.expireOverdueBookings` |
| JWT, phân quyền, CSRF | `JwtAuthenticationFilter`, `SecurityConfig` |
| Chữ ký VNPay | `VnPayService.handleCallback`; chạy `VnPayServiceTest` (8 test: ký HMAC-SHA512, chặn khi sửa số tiền, thanh toán đến muộn). Muốn cho thầy xem màn hình cổng VNPay: mở `https://sandbox.vnpayment.vn/tryitnow/Home/CreateOrder` (cửa hàng mẫu của VNPay), trả bằng thẻ NCB `9704198526191432198`, `NGUYEN VAN A`, `07/15`, OTP `123456`. |
| Toàn bộ test + độ phủ | `mvnw.cmd test` (chạy trước, khoảng 4 phút) → mở `target\site\jacoco\index.html` |

## Khi có sự cố

| Sự cố | Xử lý |
|---|---|
| Mất mạng | Mọi thứ vẫn chạy (thư viện nằm trong jar); chỉ nền bản đồ và VNPay cần mạng → dùng *Thanh toán thử*. |
| Camera không mở | Chỉ chạy trên `localhost`; gõ mã đặt vé vào ô nhập tay. |
| Dữ liệu rối sau khi thử | Tắt app, chạy lại `scripts\reset-demo.cmd`. |
| Port 8080 đang bận | Tắt app cũ, hoặc chạy với `-Dspring-boot.run.arguments=--server.port=8081`. |
