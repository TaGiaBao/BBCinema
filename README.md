# BBCinema – Hệ thống đặt vé xem phim

Đồ án môn **Kỹ năng lập trình nâng cao**, xây dựng bằng **Java 21 + Spring Boot 3**.
Giao diện lấy bố cục và luồng thao tác của các website rạp chiếu phim phổ biến làm mẫu,
với tên, logo, màu sắc và hình ảnh riêng.

## Chức năng

| Vai trò | Chức năng |
|---|---|
| Khách | Chọn rạp, xem phim đang chiếu / sắp chiếu / suất chiếu đặc biệt, chi tiết phim và trailer, lịch chiếu theo rạp, bảng giá vé, tin tức và ưu đãi, đăng ký, đăng nhập |
| Thành viên | Chọn ghế trên sơ đồ, giữ ghế 5 phút, chọn combo bắp nước, áp mã giảm giá, dùng điểm thưởng, thanh toán (thử hoặc VNPay sandbox), nhận vé có mã QR qua email, xem lịch sử vé, huỷ vé trước giờ chiếu 2 tiếng, tích điểm 5% |
| Nhân viên quầy | Bán vé tại quầy (thu tiền mặt, in vé), soát vé theo mã |
| Quản trị viên | Thống kê doanh thu có biểu đồ, quản lý phim và thể loại (tải poster), rạp, phòng chiếu và sơ đồ ghế (kéo thả tự do: chia khu, lối đi, ghế đôi ở bất kỳ đâu), suất chiếu (chặn trùng giờ), bảng giá vé, combo, mã giảm giá, tin tức, banner, tài khoản và phân quyền, danh sách đơn đặt vé |

Các điểm kỹ thuật chính:

- **Chống đặt trùng ghế**: khi giữ ghế, các dòng ghế được khoá bằng `SELECT ... FOR UPDATE`
  (`@Lock(PESSIMISTIC_WRITE)`); thêm khoá lạc quan `@Version` và ràng buộc `UNIQUE(showtime_id, seat_id)`.
  Có test cho 8 người cùng tranh một ghế, chỉ 1 người được.
- **Tự giải phóng ghế**: `@Scheduled` mỗi 30 giây huỷ các đơn chưa thanh toán quá 5 phút.
- **Bảo mật**: Spring Security + JWT (cookie HttpOnly cho trang web, header `Authorization: Bearer` cho REST API),
  mật khẩu BCrypt, phân quyền USER / STAFF / ADMIN, chống CSRF.
- **Giá vé** theo loại ghế, 2D/3D, ngày thường hay cuối tuần, trước hay sau 17h. Giá được chốt vào từng ghế
  khi tạo suất chiếu.
- **Kiểm tra độ tuổi** theo ngày sinh với phim T13, T16, T18.
- **Chuyển khoản QR (VietQR)**: mã QR có sẵn số tài khoản, số tiền, nội dung; nối **SePay** để tự xác nhận khi tiền về.
  Quầy vé nhận tiền mặt, thẻ (máy POS) và chuyển khoản QR.
- **VNPay sandbox** (ký HMAC-SHA512), email vé dạng HTML có mã QR (ZXing), gửi bất đồng bộ sau khi thanh toán.
- **REST API** có tài liệu Swagger, xử lý lỗi tập trung, kiểm tra dữ liệu với Bean Validation.
- **226 test** (JUnit 5, Mockito, MockMvc, test tích hợp với H2), báo cáo độ phủ JaCoCo.

## Công nghệ

Java 21, Spring Boot 3.4 (Web, Data JPA, Security, Validation, Mail, Thymeleaf), MySQL 8, H2,
JJWT, ZXing, springdoc-openapi, Bootstrap 5, Chart.js, Lombok, JUnit 5, Mockito, JaCoCo, Maven, Docker.

## Chạy ứng dụng

Có ba cách. Nhanh nhất là **Cách 3 (Docker)**: không cần cài Java hay MySQL.
Cách 1 và 2 cần **JDK 21**; Maven đã có sẵn trong project (`mvnw`), không cần cài riêng.

### Cách 1: chạy nhanh, không cần cài MySQL (H2)

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
```

Trên Windows dùng `mvnw.cmd` thay cho `./mvnw`. Dữ liệu lưu trong thư mục `data/`.

Ở cách này có **hộp thư demo** tại `/demo/mailbox`: xem email vé và email đặt lại mật khẩu mà không cần
tài khoản email. Muốn làm sạch dữ liệu trước buổi demo: tắt ứng dụng rồi chạy `scripts\reset-demo.cmd`.
Kịch bản trình bày nằm ở `docs/demo-script.md`.

### Cách 2: chạy với MySQL (mặc định)

1. Cài MySQL 8 và tạo tài khoản. Mặc định ứng dụng dùng `root` / `root` tại `localhost:3306`.
2. (Không bắt buộc) chạy `database/schema.sql` để tạo database. Ứng dụng cũng tự tạo bảng khi chạy.
3. Chạy:

```bash
./mvnw spring-boot:run
```

Đổi thông tin kết nối bằng biến môi trường: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`.
Trong IntelliJ: mở `BbCinemaApplication`, bấm Run. Muốn dùng H2 thì thêm `h2` vào ô *Active profiles*.

### Cách 3: Docker (khuyên dùng, không cần cài Java hay MySQL)

Chỉ cần cài [Docker Desktop](https://www.docker.com/products/docker-desktop/) và mở nó lên. Docker tự build ứng dụng và chạy kèm MySQL.

```bash
docker compose up -d --build
```

Lần đầu mất vài phút để tải image và thư viện; các lần sau chỉ vài giây. Đợi khoảng 1 phút cho ứng dụng khởi động, rồi mở **http://localhost:8080**.

| Việc | Lệnh |
|---|---|
| Xem trạng thái (cột STATUS hiện `healthy` là chạy xong) | `docker compose ps` |
| Xem log ứng dụng | `docker compose logs -f app` |
| Cập nhật sau khi sửa code | `docker compose up -d --build` |
| Tắt (giữ nguyên dữ liệu) | `docker compose down` |
| Tắt và **xoá hết dữ liệu** (tạo lại dữ liệu mẫu) | `docker compose down -v` |

- Cổng 8080 đã bị dùng (ví dụ đang chạy bằng `mvnw`)? Thêm dòng `APP_PORT=8090` vào file `.env` rồi mở http://localhost:8090.
- MySQL trong Docker mở ở cổng **3307** trên máy, xem được bằng MySQL Workbench. Ứng dụng dùng tài khoản riêng `bbcinema` (mật khẩu `bbcinema-local`), không dùng `root`. Muốn đổi mật khẩu thì đặt `LOCAL_DB_PASSWORD` và `LOCAL_MYSQL_ROOT_PASSWORD` trong `.env` **trước lần chạy đầu tiên** (hoặc chạy `docker compose down -v` để tạo lại).
- Mật khẩu Gmail, khoá VNPay, `JWT_SECRET` được đọc từ file `.env` (chép từ `.env.example`). Không có file `.env` thì vẫn chạy được, chỉ là không gửi email thật.
- Dữ liệu MySQL và ảnh tải lên nằm trong Docker volume, nên tắt rồi bật lại không bị mất.
- Bản Docker này dành cho máy cá nhân và demo: có dữ liệu mẫu, Hibernate tự tạo bảng. Lên server thật thì xem mục **Triển khai thật** bên dưới.

### Triển khai thật (profile `prod`)

Chạy với `SPRING_PROFILES_ACTIVE=mysql,prod`. Profile này (file `src/main/resources/application-prod.yml`):

- **Tắt dữ liệu mẫu**, tắt Swagger, bật cache giao diện.
- **Không có giá trị mặc định** cho mật khẩu và khoá bí mật. Ứng dụng **không chịu khởi động** nếu thiếu `JWT_SECRET`, `DB_USERNAME`, `DB_PASSWORD`, `APP_BASE_URL`, hoặc còn dùng giá trị demo (khoá JWT mẫu, tài khoản `root`, mật khẩu `root`, địa chỉ `localhost`) và báo rõ cần sửa gì.
- **Quản lý bảng bằng Flyway** (`src/main/resources/db/migration`); Hibernate chỉ kiểm tra bảng khớp với code (`ddl-auto: validate`). Database cũ do Hibernate tạo được tự nhận là phiên bản 1. Mỗi lần đổi bảng về sau thì thêm file `V2__...sql`, `V3__...sql`, không sửa file đã chạy.
- **Tài khoản DB quyền tối thiểu**: chạy `database/create-app-user.sql` (sửa mật khẩu trước) để tạo `bbcinema_app` (chỉ đọc/ghi dữ liệu, không xoá được bảng) và `bbcinema_migrate` (dùng cho Flyway).
- **Không lộ chi tiết lỗi**: trang lỗi và API không trả về thông báo exception hay stack trace.

| Biến | Bắt buộc | Ví dụ |
|---|---|---|
| `DB_HOST`, `DB_NAME` | Có | `db.example.com`, `bbcinema` |
| `DB_USERNAME`, `DB_PASSWORD` | Có | `bbcinema_app`, mật khẩu mạnh |
| `FLYWAY_USER`, `FLYWAY_PASSWORD` | Không (mặc định giống tài khoản app) | `bbcinema_migrate` |
| `JWT_SECRET` | Có | kết quả của `openssl rand -base64 32` |
| `APP_BASE_URL` | Có | `https://bbcinema.example.com` |
| `SEED_DATA`, `SWAGGER_ENABLED` | Không | `true` để bật |

## Tài khoản dùng thử

Lần chạy đầu, ứng dụng tự tạo dữ liệu mẫu:
- 10 phim (tên và poster tự tạo cho đồ án)
- 2 rạp, 6 phòng
- Suất chiếu cho 14 ngày tới, mỗi đêm tự thêm ngày mới
- Bảng giá, combo, mã giảm giá, tin tức, banner
- Khoảng 28 đơn đã thanh toán để biểu đồ có dữ liệu

| Vai trò | Email | Mật khẩu |
|---|---|---|
| Quản trị viên | admin@bbcinema.vn | Admin@123 |
| Nhân viên quầy | staff@bbcinema.vn | Staff@123 |
| Thành viên (có 30 điểm) | user@bbcinema.vn | User@123 |
| Thành viên 14 tuổi (thử chặn phim T16/T18) | teen@bbcinema.vn | Teen@123 |

Mã giảm giá: `BBCINEMA10` (giảm 10%, tối đa 30.000đ), `THANHVIEN20` (giảm 20% cho đơn từ 200.000đ),
`HETHAN` (đã hết hạn, để thử báo lỗi).

Tắt dữ liệu mẫu: đặt biến môi trường `SEED_DATA=false`. Muốn tạo lại từ đầu: xoá database (hoặc thư mục `data/` với H2).

## Cấu hình tuỳ chọn

Tất cả nằm trong `src/main/resources/application.yml`. Các giá trị bí mật (mật khẩu email, khoá VNPay...)
nên đặt trong file **`.env`** ở thư mục gốc project (cùng chỗ với `pom.xml`): chép `.env.example` thành `.env`
rồi điền giá trị. Ứng dụng tự đọc file này khi chạy, và Git bỏ qua nó nên mật khẩu không bị đẩy lên GitHub.
Cũng có thể đặt các giá trị này bằng biến môi trường.

**Gửi email vé** (Gmail): bật xác minh 2 bước, tạo *Mật khẩu ứng dụng*, rồi ghi vào `.env`

```
MAIL_ENABLED=true
MAIL_USERNAME=email-cua-ban@gmail.com
MAIL_PASSWORD=mat-khau-ung-dung
```

Khi tắt, ứng dụng chỉ ghi ra log "Ticket ... would be sent to ...".

**VNPay sandbox**: đăng ký tài khoản thử tại https://sandbox.vnpayment.vn/devreg/, sau đó đặt
`VNPAY_TMN_CODE` và `VNPAY_HASH_SECRET`. Khi chưa cấu hình, trang thanh toán chỉ cho chọn *Thanh toán thử*.
Thẻ test: ngân hàng NCB, số thẻ `9704198526191432198`, tên `NGUYEN VAN A`, ngày phát hành `07/15`, OTP `123456`.

**JWT**: đổi `JWT_SECRET` khi triển khai thật (`openssl rand -base64 32`).

### Đăng nhập bằng Google (không bắt buộc)

1. Vào [Google Cloud Console](https://console.cloud.google.com/) → **APIs & Services** → **Credentials** → **Create credentials** → **OAuth client ID** (lần đầu phải cấu hình *OAuth consent screen*, chọn *External*).
2. Loại ứng dụng: **Web application**. *Authorized redirect URIs*: `http://localhost:8080/login/oauth2/code/google` (thêm địa chỉ thật khi triển khai).
3. Chép *Client ID* và *Client secret* vào `.env`:
   ```
   GOOGLE_CLIENT_ID=...apps.googleusercontent.com
   GOOGLE_CLIENT_SECRET=...
   ```
4. Khởi động lại ứng dụng: trang đăng nhập có thêm nút **Đăng nhập bằng Google**. Không điền thì nút không hiện.

Email Google phải đã xác minh. Email mới sẽ tạo tài khoản thành viên; tài khoản nhân viên/quản trị không đăng nhập bằng Google được.

## Kiểm thử

```bash
./mvnw test
```

Báo cáo độ phủ code: `target/site/jacoco/index.html`.

Mỗi lần push hoặc mở pull request, GitHub Actions (`.github/workflows/ci.yml`) chạy toàn bộ test trước; chỉ khi test qua mới build image Docker. Kết quả và báo cáo độ phủ xem ở tab **Actions** trên GitHub.

| Nhóm test | Nội dung |
|---|---|
| `service/*Test` | Unit test với Mockito: tính giá, giữ ghế, mã giảm giá, điểm thưởng, huỷ vé, soát vé, VNPay (kể cả thanh toán đến sau khi hết giờ giữ ghế), tạo phòng và suất chiếu |
| `security/JwtServiceTest` | Tạo, kiểm tra, hết hạn và sửa token |
| `config/ProductionSafetyCheckTest` | Profile prod từ chối khởi động khi thiếu khoá bí mật hoặc còn giá trị demo |
| `integration/BookingFlowIntegrationTest` | Đặt vé trọn luồng, 8 người tranh 1 ghế, hết hạn giữ ghế, kiểm tra tuổi, bán tại quầy và soát vé |
| `integration/SecurityIntegrationTest` | Phân quyền, chuyển hướng đăng nhập, CSRF, tài khoản bị khoá |
| `integration/ApiIntegrationTest` | REST API: đăng nhập, đặt vé, lỗi 401/403/404/409/400 |
| `integration/WebPagesIntegrationTest` | Hiển thị mọi trang Thymeleaf với dữ liệu thật |

## Tài liệu API

Swagger UI: http://localhost:8080/swagger-ui.html

1. Gọi `POST /api/auth/login` với `{"email": "user@bbcinema.vn", "password": "User@123"}`.
2. Bấm **Authorize** và dán `accessToken`.

| Phương thức | Đường dẫn | Quyền |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | Mọi người |
| GET | `/api/cinemas`, `/api/cinemas/{id}/showtimes?date=` | Mọi người |
| GET | `/api/movies?q=&status=&genreId=`, `/api/movies/{id}`, `/api/movies/{id}/showtimes?cinemaId=&date=` | Mọi người |
| GET | `/api/showtimes/{id}`, `/api/showtimes/{id}/seats` | Mọi người |
| GET | `/api/ticket-prices`, `/api/combos` | Mọi người |
| POST | `/api/bookings` (giữ ghế) | Thành viên |
| GET | `/api/bookings/me`, `/api/bookings/{code}` | Thành viên |
| PUT | `/api/bookings/{code}/combos` | Thành viên |
| POST | `/api/bookings/{code}/promotion`, `/api/bookings/{code}/points`, `/api/bookings/{code}/pay` | Thành viên |
| DELETE | `/api/bookings/{code}` (huỷ) | Thành viên |
| GET | `/api/admin/reports/revenue?from=&to=`, `/api/admin/reports/movies?from=&to=` | Quản trị |

## Cấu trúc thư mục

```
src/main/java/com/bbcinema
├── config/        Cấu hình chung, dữ liệu mẫu (DataSeeder), Swagger, thư mục ảnh tải lên
├── security/      JWT, Spring Security, thông tin người đăng nhập
├── entity/        Lớp ánh xạ bảng (JPA) và enum
├── repository/    Truy vấn dữ liệu (Spring Data JPA)
├── dto/           Dữ liệu vào (form, request) và ra (response)
├── service/       Nghiệp vụ: đặt vé, giá vé, suất chiếu, khuyến mãi, điểm, VNPay, email, thống kê
├── web/           Controller trang web (Thymeleaf): khách, thành viên, admin/, staff/
├── api/           REST API và xử lý lỗi JSON
├── scheduler/     Tác vụ định kỳ giải phóng ghế giữ quá hạn
├── exception/     Ngoại lệ nghiệp vụ
└── util/          Tạo slug, mã vé
src/main/resources
├── templates/     Giao diện Thymeleaf (layout, trang khách, thành viên, đặt vé, admin, staff, email)
├── static/        CSS, JavaScript (sơ đồ ghế, đếm ngược, popup suất chiếu), ảnh
└── application.yml
database/schema.sql   Script tạo database MySQL
scripts/              Script sinh poster, banner dạng SVG cho dữ liệu mẫu
```
